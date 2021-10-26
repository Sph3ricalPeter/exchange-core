package exchange.core2.tests.examples;

import static org.junit.Assert.assertEquals;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.ExchangeCore;
import exchange.core2.core.SimpleEventsProcessor;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.FeeZone;
import exchange.core2.core.common.L2MarketData;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.ApiAdjustUserBalance;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.api.binary.BatchAddAccountsCommand;
import exchange.core2.core.common.api.binary.BatchAddSymbolsCommand;
import exchange.core2.core.common.api.binary.BatchUpdateUserFeeZones;
import exchange.core2.core.common.api.reports.SingleUserReportQuery;
import exchange.core2.core.common.api.reports.SingleUserReportResult;
import exchange.core2.core.common.api.reports.TotalCurrencyBalanceReportQuery;
import exchange.core2.core.common.api.reports.TotalCurrencyBalanceReportResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.common.config.InitialStateConfiguration;
import exchange.core2.core.common.config.LoggingConfiguration;
import exchange.core2.core.common.config.LoggingConfiguration.LoggingLevel;
import exchange.core2.core.common.config.OrdersProcessingConfiguration;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.common.config.ReportsQueriesConfiguration;
import exchange.core2.core.common.config.SerializationConfiguration;
import exchange.core2.core.utils.Convert;
import exchange.core2.core.utils.Currency;
import exchange.core2.core.utils.CurrencyPair;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.junit.Test;

@Slf4j
public class ITCoreHiddenOrderExample {

  private static final Currency CURRENCY_BTC = new Currency(11, 100_000_000L);
  private static final Currency CURRENCY_LTC = new Currency(15, 100_000_000L);

  private static final CurrencyPair PAIR_LTC_BTC =
      new CurrencyPair(241, CURRENCY_BTC, CURRENCY_LTC, 10_000L, 1L);

  private static final CoreSymbolSpecification SYMBOL_SPEC_LTC_BTC =
      CoreSymbolSpecification.builder()
          .symbolId(PAIR_LTC_BTC.getId())
          .type(SymbolType.CURRENCY_EXCHANGE_PAIR)
          .baseCurrency(CURRENCY_LTC.getId())
          .quoteCurrency(CURRENCY_BTC.getId())
          .baseScaleK(PAIR_LTC_BTC.getBaseScale())
          .quoteScaleK(PAIR_LTC_BTC.getQuoteScale())
          .takerBaseFee(0L)
          .makerBaseFee(0L)
          .build();

  @Test
  public void sampleTest() throws Exception {

    // simple async events handler
    SimpleEventsProcessor eventsProcessor = SimpleEventsProcessor.LOG_EVENTS;

    // test config with snapshotting enabled
    ExchangeConfiguration conf =
        ExchangeConfiguration.builder()
            .ordersProcessingCfg(OrdersProcessingConfiguration.DEFAULT)
            .initStateCfg(InitialStateConfiguration.cleanStart("MY_EXCHANGE"))
            .performanceCfg(PerformanceConfiguration.DEFAULT)
            .reportsQueriesCfg(ReportsQueriesConfiguration.DEFAULT)
            .loggingCfg(
                LoggingConfiguration.builder()
                    .loggingLevels(
                        EnumSet.of(
                            LoggingLevel.LOGGING_WARNINGS,
                            LoggingLevel.LOGGING_MATCHING_DEBUG,
                            LoggingLevel.LOGGING_RISK_DEBUG))
                    .build())
            .serializationCfg(
                SerializationConfiguration
                    .DISK_SNAPSHOT_ONLY_REPLACE) // default disk journaling to the `dumps` folder
            .build();

    // build exchange core
    ExchangeCore exchangeCore =
        ExchangeCore.builder().resultsConsumer(eventsProcessor).exchangeConfiguration(conf).build();

    // start up disruptor threads
    exchangeCore.startup();

    // get exchange API for publishing commands
    ExchangeApi api = exchangeCore.getApi();

    // SYMBOLS
    List<CoreSymbolSpecification> symbols = new ArrayList<>();
    symbols.add(SYMBOL_SPEC_LTC_BTC);
    api.submitBinaryDataAsync(new BatchAddSymbolsCommand(symbols));

    // ACCOUNTS & BALANCES
    // we can use batch add users to efficiently init all users and their balance
    LongObjectHashMap<IntLongHashMap> userAccounts = new LongObjectHashMap<>();
    userAccounts.put(
        301L,
        IntLongHashMap.newWithKeysValues(CURRENCY_BTC.getId(), 3 * CURRENCY_BTC.getNUnits(), CURRENCY_LTC.getId(), 0));
    userAccounts.put(
        302L,
        IntLongHashMap.newWithKeysValues(CURRENCY_BTC.getId(), 0, CURRENCY_LTC.getId(), 3 * CURRENCY_LTC.getNUnits()));

    CompletableFuture<CommandResultCode> future =
        api.submitBinaryDataAsync(
            new BatchAddAccountsCommand(userAccounts, FeeZone.fromPercent(0.08, 0.2)));

    Future<SingleUserReportResult> u1Report =
        api.processReport(new SingleUserReportQuery(301L), 0);
    log.info(u1Report.get().toString());

    Future<SingleUserReportResult> u2Report =
        api.processReport(new SingleUserReportQuery(302L), 0);
    log.info(u2Report.get().toString());

    // ORDERS
    BigDecimal sizeInput = new BigDecimal("2");
    BigDecimal priceInput = new BigDecimal("0.003");

    // conversion
    long pricePerLotScaled = Convert.priceToPricePerLot(PAIR_LTC_BTC, priceInput);
    long sizeLots = Convert.sizeToLots(PAIR_LTC_BTC, sizeInput);
    long totalPriceScaled = Convert.calcTotalScaled(PAIR_LTC_BTC, sizeLots, pricePerLotScaled);
    log.info(
        "pricePerLotScaled: {}, sizeLots: {}, totalPriceScaled: {}",
        pricePerLotScaled,
        sizeLots,
        totalPriceScaled);

    // maker sell full size but hidden, pays taker fee for all of it
    future =
        api.submitCommandAsync(
            ApiPlaceOrder.builder()
                .uid(302L)
                .orderId(5001L)
                .price(pricePerLotScaled)
                .reservePrice(pricePerLotScaled)
                .size(sizeLots)
                .action(OrderAction.ASK)
                .orderType(OrderType.GTC)
                .symbol(PAIR_LTC_BTC.getId())
                .hidden(false) // order is not hidden, should appear in OB
                .build());

    future.get();

    CompletableFuture<L2MarketData> orderBookFuture =
        api.requestOrderBookAsync(PAIR_LTC_BTC.getId(), 10);
    log.info("ApiOrderBookRequest result: " + orderBookFuture.get());

    // taker buy half size, pays taker fee
    future =
        api.submitCommandAsync(
            ApiPlaceOrder.builder()
                .uid(301L)
                .orderId(5002L)
                .price(pricePerLotScaled)
                .reservePrice(pricePerLotScaled)
                .size(Math.round(sizeLots / 2D))
                .action(OrderAction.BID)
                .orderType(OrderType.GTC)
                .symbol(PAIR_LTC_BTC.getId())
                .hidden(true) // order is hidden, shouldn't appear in order book
                .build());

    future.get();

    orderBookFuture = api.requestOrderBookAsync(PAIR_LTC_BTC.getId(), 10);
    log.info("ApiOrderBookRequest result: " + orderBookFuture.get());

    // taker buy for half size, maker for other half
    // pays taker fee for everything because its hidden order
    future =
        api.submitCommandAsync(
            ApiPlaceOrder.builder()
                .uid(301L)
                .orderId(5003L)
                .price(pricePerLotScaled)
                .reservePrice(pricePerLotScaled)
                .size(sizeLots)
                .action(OrderAction.BID)
                .orderType(OrderType.GTC)
                .symbol(PAIR_LTC_BTC.getId())
                .hidden(false)
                .build());

    future.get();

    orderBookFuture = api.requestOrderBookAsync(PAIR_LTC_BTC.getId(), 10);
    log.info("ApiOrderBookRequest result: " + orderBookFuture.get());

    // taker sell half size, pays taker fee
    future =
        api.submitCommandAsync(
            ApiPlaceOrder.builder()
                .uid(302L)
                .orderId(5004L)
                .price(pricePerLotScaled)
                .reservePrice(pricePerLotScaled)
                .size(Math.round(sizeLots / 2D))
                .action(OrderAction.ASK)
                .orderType(OrderType.GTC)
                .symbol(PAIR_LTC_BTC.getId())
                .hidden(false) // order is not hidden, should appear in OB
                .build());

    future.get();

    orderBookFuture = api.requestOrderBookAsync(PAIR_LTC_BTC.getId(), 10);
    log.info("ApiOrderBookRequest result: " + orderBookFuture.get());

    u1Report =
        api.processReport(new SingleUserReportQuery(301L), 0);
    log.info(u1Report.get().toString());

    u2Report =
        api.processReport(new SingleUserReportQuery(302L), 0);
    log.info(u2Report.get().toString());

    Future<TotalCurrencyBalanceReportResult> balancesReportBeforeSnapshot =
        api.processReport(new TotalCurrencyBalanceReportQuery(), 0);
    log.info(balancesReportBeforeSnapshot.get().toString());

    // exchangeCore.shutdown();
  }
}

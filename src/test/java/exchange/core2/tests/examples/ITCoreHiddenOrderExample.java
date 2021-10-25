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
            .performanceCfg(PerformanceConfiguration.DEFAULT) // balanced perf. config, naive impl
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
    userAccounts.put(301L, new IntLongHashMap());

    CompletableFuture<CommandResultCode> future = api.submitBinaryDataAsync(
        new BatchAddAccountsCommand(userAccounts, FeeZone.ZERO));

    // DEPOSITS
    future = api.submitCommandAsync(
        ApiAdjustUserBalance.builder()
            .uid(301L)
            .currency(CURRENCY_BTC.getId())
            .amount(1 * CURRENCY_BTC.getNUnits())
            .transactionId(2001L)
            .build());

    // ORDERS
    BigDecimal sizeInput = new BigDecimal("1");
    BigDecimal priceInput = new BigDecimal("0.003");
    BigDecimal totalInput = null;

    // conversion
    long pricePerLotScaled = Convert.priceToPricePerLot(PAIR_LTC_BTC, priceInput);
    long sizeLots = Convert.sizeToLots(PAIR_LTC_BTC, sizeInput);
    long totalPriceScaled = Convert.calcTotalScaled(PAIR_LTC_BTC, sizeLots, pricePerLotScaled);
    log.info(
        "pricePerLotScaled: {}, sizeLots: {}, totalPriceScaled: {}",
        pricePerLotScaled,
        sizeLots,
        totalPriceScaled);

    future =
        api.submitCommandAsync(
            ApiPlaceOrder.builder()
                .uid(301L)
                .orderId(5001L)
                .price(pricePerLotScaled)
                .reservePrice(pricePerLotScaled)
                .size(sizeLots)
                .action(OrderAction.BID)
                .orderType(OrderType.GTC)
                .symbol(PAIR_LTC_BTC.getId())
                .hidden(true) // order is hidden, shouldn't appear in order book
                .build());

    CompletableFuture<L2MarketData> orderBookFuture1 =
        api.requestOrderBookAsync(PAIR_LTC_BTC.getId(), 10);
    log.info("ApiOrderBookRequest result: " + orderBookFuture1.get());

    future =
        api.submitCommandAsync(
            ApiPlaceOrder.builder()
                .uid(301L)
                .orderId(5002L)
                .price(pricePerLotScaled)
                .reservePrice(pricePerLotScaled)
                .size(sizeLots)
                .action(OrderAction.BID)
                .orderType(OrderType.GTC)
                .symbol(PAIR_LTC_BTC.getId())
                .hidden(false) // order is visible, should appear in OB
                .build());

    CompletableFuture<L2MarketData> orderBookFuture2 =
        api.requestOrderBookAsync(PAIR_LTC_BTC.getId(), 10);
    log.info("ApiOrderBookRequest result: " + orderBookFuture2.get());

    exchangeCore.shutdown();
  }
}

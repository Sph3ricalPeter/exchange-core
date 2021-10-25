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
import exchange.core2.core.common.api.reports.SingleUserReportQuery;
import exchange.core2.core.common.api.reports.SingleUserReportResult;
import exchange.core2.core.common.api.reports.TotalCurrencyBalanceReportQuery;
import exchange.core2.core.common.api.reports.TotalCurrencyBalanceReportResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.common.config.ExchangeConfiguration.ExchangeConfigurationBuilder;
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
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.junit.Test;

@SuppressWarnings("SpellCheckingInspection")
@Slf4j
public class ITCoreConversions {

  private static final String EXCHANGE_ID = "TEST_EXCHANGE";

  private static final FeeZone TEST_FEE_ZONE = FeeZone.fromPercent(0.08, 0.2);

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

  public static ExchangeConfigurationBuilder testExchangeConfCleanBuilder() {
    return ExchangeConfiguration.builder()
        .ordersProcessingCfg(OrdersProcessingConfiguration.DEFAULT)
        .initStateCfg(InitialStateConfiguration.cleanStart(EXCHANGE_ID))
        .performanceCfg(PerformanceConfiguration.DEFAULT) // balanced perf. config
        .reportsQueriesCfg(ReportsQueriesConfiguration.DEFAULT)
        .loggingCfg(
            LoggingConfiguration.builder()
                .loggingLevels(
                    EnumSet.of(LoggingLevel.LOGGING_WARNINGS, LoggingLevel.LOGGING_RISK_DEBUG))
                .build())
        .serializationCfg(
            SerializationConfiguration
                .DISK_SNAPSHOT_ONLY_REPLACE); // default disk journaling to the `dumps` folder
    // this configuration automatically replaces files if they already exist
  }

  @Test
  public void testCleanStartInitShutdownThenStartFromSnapshot_balanceReportsShouldEqual()
      throws Exception {

    ExchangeConfiguration conf = testExchangeConfCleanBuilder().build();
    ExchangeCore ec =
        ExchangeCore.builder()
            .resultsConsumer(SimpleEventsProcessor.LOG_EVENTS)
            .exchangeConfiguration(conf)
            .build();
    ec.startup();

    ExchangeApi api = ec.getApi();

    // SYMBOLS - we pass symbol (currency pair) specifications to the core in a batch
    List<CoreSymbolSpecification> symbols = new ArrayList<>();
    symbols.add(SYMBOL_SPEC_LTC_BTC);

    Future<CommandResultCode> future =
        api.submitBinaryDataAsync(new BatchAddSymbolsCommand(symbols));
    log.info("BatchAddSymbolsCommand result: " + future.get());

    // ACCOUNTS & BALANCES
    // we can use batch add users to efficiently init all users and their balance
    LongObjectHashMap<IntLongHashMap> userAccounts = new LongObjectHashMap<>();

    IntLongHashMap u1Accounts = new IntLongHashMap();
    u1Accounts.put(CURRENCY_BTC.getId(), 0);
    u1Accounts.put(CURRENCY_LTC.getId(), 0);
    userAccounts.put(301L, u1Accounts);

    IntLongHashMap u2Accounts = new IntLongHashMap();
    u2Accounts.put(CURRENCY_BTC.getId(), 0);
    u2Accounts.put(CURRENCY_LTC.getId(), 0);
    userAccounts.put(302L, u2Accounts);

    // set fees of all initialized users to sub 10k volume
    future = api.submitBinaryDataAsync(new BatchAddAccountsCommand(userAccounts, TEST_FEE_ZONE));
    log.info("BatchAddAccountsCommand result: " + future.get());

    // DEPOSITS
    future =
        api.submitCommandAsync(
            ApiAdjustUserBalance.builder()
                .uid(301L)
                .currency(CURRENCY_BTC.getId())
                .amount(1 * CURRENCY_BTC.getNUnits())
                .transactionId(2001L)
                .build());

    log.info("ApiAdjustUserBalance 1 result: " + future.get());

    future =
        api.submitCommandAsync(
            ApiAdjustUserBalance.builder()
                .uid(302L)
                .currency(CURRENCY_LTC.getId())
                .amount(1 * CURRENCY_LTC.getNUnits()) // in litoshi
                .transactionId(2002L)
                .build());

    log.info("ApiAdjustUserBalance 2 result: " + future.get());

    // ORDERS
    // input, known price and total, calc size
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
                .build());

    log.info("ApiPlaceOrder 1 result: " + future.get());

    // input, known price and size
    sizeInput = null;
    priceInput = new BigDecimal("0.003");
    totalInput = new BigDecimal("0.003");

    pricePerLotScaled = Convert.priceToPricePerLot(PAIR_LTC_BTC, priceInput);
    totalPriceScaled =
        totalInput.multiply(new BigDecimal(PAIR_LTC_BTC.getQuote().getNUnits())).longValue();
    sizeLots = totalPriceScaled / pricePerLotScaled;
    log.info(
        "pricePerLotScaled: {}, sizeLots: {}, totalPriceScaled: {}",
        pricePerLotScaled,
        sizeLots,
        totalPriceScaled);

    future =
        api.submitCommandAsync(
            ApiPlaceOrder.builder()
                .uid(302L)
                .orderId(5002L)
                .price(pricePerLotScaled)
                .size(sizeLots)
                .action(OrderAction.ASK)
                .orderType(OrderType.IOC)
                .symbol(PAIR_LTC_BTC.getId())
                .build());

    log.info("ApiPlaceOrder 2 result: " + future.get());

    // GET DATA
    // request order book
    CompletableFuture<L2MarketData> orderBookFuture1 =
        api.requestOrderBookAsync(PAIR_LTC_BTC.getId(), 10);
    log.info("ApiOrderBookRequest result: " + orderBookFuture1.get());

    Future<SingleUserReportResult> u1Report2 =
        api.processReport(new SingleUserReportQuery(301L), 0);
    log.info(u1Report2.get().toString());

    Future<SingleUserReportResult> u2Report2 =
        api.processReport(new SingleUserReportQuery(302L), 0);
    log.info(u2Report2.get().toString());

    Future<TotalCurrencyBalanceReportResult> balancesReportBeforeSnapshot =
        api.processReport(new TotalCurrencyBalanceReportQuery(), 0);
    log.info(balancesReportBeforeSnapshot.get().toString());

    ec.shutdown();
  }

  @Test
  public void testConvertPrice_havePriceInBD_shouldConvertCorrectly() {
    BigDecimal price = new BigDecimal("0.003");
    long expected = 30L; // 10_000 lots -> 0.003 / 10_000 * 100_000_000
    long actual = Convert.priceToPricePerLot(PAIR_LTC_BTC, price);
    assertEquals(expected, actual);
  }

  @Test
  public void testConvertSize_haveSize_shouldConvertCorrectly() {
    BigDecimal size = new BigDecimal("0.001");
    long expected = 10L; // 0.001 * 100_000_000 units / 10_000 units per lot
    long actual = Convert.sizeToLots(PAIR_LTC_BTC, size);
    assertEquals(expected, actual);
  }

  @Test
  public void testConvertPrice_haveLowPrice_shouldHaveRoundingError() {
    BigDecimal price = new BigDecimal("0.00001");
    long expected = 0;
    long actual =
        Convert.priceToPricePerLot(
            PAIR_LTC_BTC, price); // price per lot is 0.1, gets rounded to 0, so it fits into long
    assertEquals(expected, actual);
  }

  @Test
  public void testFeeZones_doSimpleTrade_totalFeesShouldMatch()
      throws ExecutionException, InterruptedException {
    ExchangeConfiguration conf = testExchangeConfCleanBuilder().build();
    ExchangeCore ec =
        ExchangeCore.builder()
            .resultsConsumer(SimpleEventsProcessor.LOG_EVENTS)
            .exchangeConfiguration(conf)
            .build();
    ec.startup();

    ExchangeApi api = ec.getApi();

    // SYMBOLS
    List<CoreSymbolSpecification> symbols = new ArrayList<>();
    symbols.add(SYMBOL_SPEC_LTC_BTC);
    api.submitBinaryDataAsync(new BatchAddSymbolsCommand(symbols));

    // ACCOUNTS & BALANCES
    LongObjectHashMap<IntLongHashMap> userAccounts = new LongObjectHashMap<>();
    final long BUYER_MAKER = 301L;
    final long SELLER_TAKER = 302L;

    userAccounts.put(BUYER_MAKER, new IntLongHashMap());
    userAccounts.put(SELLER_TAKER, new IntLongHashMap());

    // FEES
    api.submitBinaryDataAsync(new BatchAddAccountsCommand(userAccounts, TEST_FEE_ZONE));

    // DEPOSITS
    api.submitCommandAsync(
        ApiAdjustUserBalance.builder()
            .uid(BUYER_MAKER)
            .currency(CURRENCY_BTC.getId())
            .amount(1 * CURRENCY_BTC.getNUnits())
            .transactionId(2001L)
            .build());

    api.submitCommandAsync(
        ApiAdjustUserBalance.builder()
            .uid(SELLER_TAKER)
            .currency(CURRENCY_LTC.getId())
            .amount(1 * CURRENCY_LTC.getNUnits()) // in litoshi
            .transactionId(2002L)
            .build());

    // ORDERS
    BigDecimal sizeInput = new BigDecimal("1");
    BigDecimal priceInput = new BigDecimal("0.003");
    BigDecimal totalInput = null;
    BigDecimal makerFee = priceInput.multiply(new BigDecimal(TEST_FEE_ZONE.makerFeeFraction));

    // conversion
    long pricePerLotScaled = Convert.priceToPricePerLot(PAIR_LTC_BTC, priceInput);
    long sizeLots = Convert.sizeToLots(PAIR_LTC_BTC, sizeInput);
    long totalPriceScaled = Convert.calcTotalScaled(PAIR_LTC_BTC, sizeLots, pricePerLotScaled);

    long makerFeesExpected = Math.round(totalPriceScaled * TEST_FEE_ZONE.makerFeeFraction);

    CompletableFuture<CommandResultCode> future =
        api.submitCommandAsync(
            ApiPlaceOrder.builder()
                .uid(BUYER_MAKER)
                .orderId(5001L)
                .price(pricePerLotScaled)
                .reservePrice(pricePerLotScaled)
                .size(sizeLots)
                .action(OrderAction.BID)
                .orderType(OrderType.GTC)
                .symbol(PAIR_LTC_BTC.getId())
                .build());

    log.info("ApiPlaceOrder 1 result: " + future.get());

    // input, known price and size
    sizeInput = null;
    priceInput = new BigDecimal("0.003");
    totalInput = new BigDecimal("0.003");
    BigDecimal takerFee = priceInput.multiply(new BigDecimal(TEST_FEE_ZONE.takerFeeFraction));

    pricePerLotScaled = Convert.priceToPricePerLot(PAIR_LTC_BTC, priceInput);
    totalPriceScaled =
        totalInput.multiply(new BigDecimal(PAIR_LTC_BTC.getQuote().getNUnits())).longValue();
    sizeLots = totalPriceScaled / pricePerLotScaled;

    long takerFeesExpected = Math.round(totalPriceScaled * TEST_FEE_ZONE.takerFeeFraction);

    future =
        api.submitCommandAsync(
            ApiPlaceOrder.builder()
                .uid(SELLER_TAKER)
                .orderId(5002L)
                .price(pricePerLotScaled)
                .size(sizeLots)
                .action(OrderAction.ASK)
                .orderType(OrderType.IOC)
                .symbol(PAIR_LTC_BTC.getId())
                .build());

    log.info("ApiPlaceOrder 2 result: " + future.get());

    // GET DATA
    // request order book
    CompletableFuture<L2MarketData> orderBookFuture1 =
        api.requestOrderBookAsync(PAIR_LTC_BTC.getId(), 10);
    log.info("ApiOrderBookRequest result: " + orderBookFuture1.get());

    Future<SingleUserReportResult> u1Report2 =
        api.processReport(new SingleUserReportQuery(BUYER_MAKER), 0);
    log.info(u1Report2.get().toString());

    Future<SingleUserReportResult> u2Report2 =
        api.processReport(new SingleUserReportQuery(SELLER_TAKER), 0);
    log.info(u2Report2.get().toString());

    Future<TotalCurrencyBalanceReportResult> balancesReport =
        api.processReport(new TotalCurrencyBalanceReportQuery(), 0);
    log.info(balancesReport.get().toString());

    ec.shutdown();

    long feesScaledExpected = makerFeesExpected + takerFeesExpected;
    long feesScaledActual = balancesReport.get().getFees().get(CURRENCY_BTC.getId());

    BigDecimal feesExpected = makerFee.add(takerFee).round(new MathContext(10));
    BigDecimal feesActual = BigDecimal.valueOf(
        (double) feesScaledActual / CURRENCY_BTC.getNUnits());

    assertEquals(feesScaledExpected, feesScaledActual);
    assertEquals(0, feesExpected.compareTo(feesActual));
  }
}

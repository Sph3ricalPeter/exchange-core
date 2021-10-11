package exchange.core2.tests.examples;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.ExchangeCore;
import exchange.core2.core.IEventsHandler;
import exchange.core2.core.IEventsHandler.TradeEvent;
import exchange.core2.core.SimpleEventsProcessor;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.L2MarketData;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.ApiAdjustUserBalance;
import exchange.core2.core.common.api.ApiPersistState;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.api.binary.BatchAddAccountsCommand;
import exchange.core2.core.common.api.binary.BatchAddSymbolsCommand;
import exchange.core2.core.common.api.reports.ReportQuery;
import exchange.core2.core.common.api.reports.ReportType;
import exchange.core2.core.common.api.reports.SingleUserReportQuery;
import exchange.core2.core.common.api.reports.SingleUserReportResult;
import exchange.core2.core.common.api.reports.SymbolsReportQuery;
import exchange.core2.core.common.api.reports.SymbolsReportResult;
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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.NotImplementedException;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.junit.Test;

/**
 * This is an extended example test based on {@link ITCoreExample} This test features most api
 * commands as well as real-world configuration scenario
 *
 * @author Petr Ježek
 */
@SuppressWarnings("SpellCheckingInspection")
@Slf4j
public class ITCoreSnapshottingExample {

  private static final String EXCHANGE_ID = "TEST_EXCHANGE";

  private static final long UNITS_PER_BTC = 100_000_000L;
  private static final long UNITS_PER_LTC = 100_000_000L;

  private static final int CURRENCY_EUR = 10;
  private static final int CURRENCY_BTC = 11;
  private static final int CURRENCY_LTC = 15;

  private static final int SYMBOL_BTC_EUR = 240;
  private static final int SYMBOL_LTC_BTC = 241;

  // symbol specification for the pair XBT/EUR
  private static final CoreSymbolSpecification SYMBOL_SPEC_BTC_EUR =
      CoreSymbolSpecification.builder()
          .symbolId(SYMBOL_BTC_EUR) // symbol id
          .type(SymbolType.CURRENCY_EXCHANGE_PAIR)
          .baseCurrency(CURRENCY_BTC) // base = satoshi (1E-8)
          .quoteCurrency(CURRENCY_EUR) // quote = cents (1E-2)
          .baseScaleK(1_000_000L) // 1 lot = 1M satoshi (0.01 BTC)
          .quoteScaleK(100L) // 1 price step = 100 cents (1 EUR), can buy BTC with 1 EUR steps
          .takerFee(1L) // taker fee 1 cent per 1 lot
          .makerFee(3L) // maker fee 3 cents per 1 lot
          .build();

  // symbol specification for the pair LTC/XBT
  // scales are defined to meet our current precision requirement
  private static final CoreSymbolSpecification SYMBOL_SPEC_LTC_BTC =
      CoreSymbolSpecification.builder()
          .symbolId(SYMBOL_LTC_BTC) // symbol id
          .type(SymbolType.CURRENCY_EXCHANGE_PAIR)
          .baseCurrency(CURRENCY_LTC) // base = satoshi (1E-8)
          .quoteCurrency(CURRENCY_BTC) // quote = litoshi (1E-8)
          .baseScaleK(100_000L) // 1 price step = 10_000 litoshi (0.0001LTC)
          .quoteScaleK(100L) // 1 lot = 1 satoshi (0.000001 BTC)
          .takerFee(0L) // can't use base fees with scale of 1, will be solved with % fees from volume hopefully
          .makerFee(0L) // can't use base fees with scale of 1, will be solved with % fees from volume hopefully
          .build();

  // initialize custom query for retreiving symbols (currency pairs from the core)
  public static Map<Integer, Class<? extends ReportQuery<?>>> createCustomReports() {
    final Map<Integer, Class<? extends ReportQuery<?>>> queries = new HashMap<>();
    queries.put(ReportType.SYMBOLS.getCode(), SymbolsReportQuery.class);
    return queries;
  }

  // configuration for a clean start of the exchange core, initial state is empty
  // everything needs to be initialized by the test
  public static ExchangeConfigurationBuilder testExchangeConfCleanBuilder() {
    return ExchangeConfiguration.builder()
        .ordersProcessingCfg(OrdersProcessingConfiguration.DEFAULT)
        .initStateCfg(InitialStateConfiguration.cleanStart(EXCHANGE_ID))
        .performanceCfg(PerformanceConfiguration.DEFAULT) // balanced perf. config
        .reportsQueriesCfg(
            ReportsQueriesConfiguration.createStandardConfig(
                ITCoreSnapshottingExample.createCustomReports() // create report  configuration
                // with the use of a custom reports
                ))
        .loggingCfg(
            LoggingConfiguration.builder()
                .loggingLevels(EnumSet.of(LoggingLevel.LOGGING_WARNINGS, LoggingLevel.LOGGING_RISK_DEBUG))
                .build())
        .serializationCfg(
            SerializationConfiguration
                .DISK_SNAPSHOT_ONLY_REPLACE); // default disk journaling to the `dumps` folder
    // this configuration automatically replaces files if they already exist
  }

  // configuration for start from an existing snapshot, snapshot with given ID needs to be saved
  // in the /dumps folder, otherwise this will fail
  // snapshot ID needs to be generated and persisted outside the core
  public ExchangeConfigurationBuilder testExchangeConfFromSnapshot(long snapshotId, long baseSeq) {
    return testExchangeConfCleanBuilder()
        .initStateCfg(InitialStateConfiguration.fromSnapshotOnly(EXCHANGE_ID, snapshotId, baseSeq));
  }

  /**
   * This test uses most ExchangeAPI's commands Its purpose is to showcase the exchange-core's
   * functionality
   *
   * <p>The test initilizes the core from an empty state and creates a snapshot The core is then
   * shut down and loaded again from the snapshot
   *
   * <p>If everything works correctly, the test should succeed comparing the totals report from
   * before and after the core restart, indicating that all data has been saved and loaded correctly
   *
   * @throws ExecutionException
   * @throws InterruptedException
   */
  @Test
  public void testCleanStartInitShutdownThenStartFromSnapshot_balanceReportsShouldEqual()
      throws Exception {

    // we store trade history in a list to simulate a DB
    List<TradeEvent> tradeHistory = new ArrayList<>();

    /* ========= CLEAN START ========= */
    log.info("Starting clean");

    ExchangeConfiguration conf = testExchangeConfCleanBuilder().build();
    ExchangeCore ec =
        ExchangeCore.builder()
            .resultsConsumer(new SimpleEventsProcessor(new TestEventHandler(tradeHistory)))
            .exchangeConfiguration(conf)
            .build();
    ec.startup();

    ExchangeApi api = ec.getApi();

    // SYMBOLS - we pass symbol (currency pair) specifications to the core in a batch
    List<CoreSymbolSpecification> symbols = new ArrayList<>();
    symbols.add(SYMBOL_SPEC_BTC_EUR);
    symbols.add(SYMBOL_SPEC_LTC_BTC);

    Future<CommandResultCode> future =
        api.submitBinaryDataAsync(new BatchAddSymbolsCommand(symbols));
    log.info("BatchAddSymbolsCommand result: " + future.get());

    // we can verify that symbols got added succesfully
    Future<SymbolsReportResult> symbolsReport0 = api.processReport(new SymbolsReportQuery(), 0);
    log.info(symbolsReport0.get().toString());

    // ACCOUNTS & BALANCES
    // we can use batch add users to efficiently init all users and their balance
    LongObjectHashMap<IntLongHashMap> userAccounts = new LongObjectHashMap<>();

    IntLongHashMap u1Accounts = new IntLongHashMap();
    u1Accounts.put(CURRENCY_EUR, 0);
    u1Accounts.put(CURRENCY_BTC, 0);
    u1Accounts.put(CURRENCY_LTC, 0);
    userAccounts.put(301L, u1Accounts);

    IntLongHashMap u2Accounts = new IntLongHashMap();
    u2Accounts.put(CURRENCY_EUR, 0);
    u2Accounts.put(CURRENCY_BTC, 0);
    u2Accounts.put(CURRENCY_LTC, 0);
    userAccounts.put(302L, u2Accounts);

    future = api.submitBinaryDataAsync(new BatchAddAccountsCommand(userAccounts));
    log.info("BatchAddAccountsCommand result: " + future.get());

    // DEPOSITS
    // first user deposits 5 BTC
    future =
        api.submitCommandAsync(
            ApiAdjustUserBalance.builder()
                .uid(301L)
                .currency(CURRENCY_BTC)
                .amount(5 * UNITS_PER_BTC) // in satoshi
                .transactionId(2001L) // transaction id has to be > 1002 because batchAddUsers balance adjustment sets last transaction id to 1000, needs to be replaced with a sequence generated value
                .build());

    log.info("ApiAdjustUserBalance 1 result: " + future.get());

    // second user deposits 20 LTC
    future =
        api.submitCommandAsync(
            ApiAdjustUserBalance.builder()
                .uid(302L)
                .currency(CURRENCY_LTC)
                .amount(20 * UNITS_PER_LTC) // in litoshi
                .transactionId(2002L)
                .build());

    log.info("ApiAdjustUserBalance 2 result: " + future.get());

    // ORDERS
    // user creates a buy order of 12 LTC and price 1 / 154 ~ 0,0064935 BTC
    // fees are equal to 0 for this example
    // the user submits price and total fields, size is calculated
    BigDecimal sizeInput = null;
    BigDecimal priceInput = new BigDecimal(Double.toString(1D / 154));
    BigDecimal totalInput = new BigDecimal(Double.toString(12D / 154));

    long pricePerLot = convert(priceInput, SYMBOL_LTC_BTC);
    long totalPrice = convert(totalInput, SYMBOL_LTC_BTC);
    long sizeCalc = totalPrice / pricePerLot;
    log.info("totalPrice {}, pricePerLot {}, sizeCalc {}", totalPrice, pricePerLot, sizeCalc);

    future =
        api.submitCommandAsync(
            ApiPlaceOrder.builder()
                .uid(301L)
                .orderId(5001L)
                .price(pricePerLot) // assume 154LTC per 1BTC, so 1LTC costs 1/154BTC, that's 100_000_000/154 satoshi = 649_350 satoshi ~ price=649_350 * quoteScale=1 satoshi per 1 LTC, that's 650_000 / 10_000 = 65 per 1 lot of LTC
                .reservePrice(pricePerLot) // can move bid order up to price of 70 lots of BTC without order cancel (700_000 satoshi) ~ 142,8 LTC per 1BTC
                .size(sizeCalc) // order size is 12 lots (so im buying size=12 * baseScale=1_000_000 litoshi = 12_000_000 litoshi, im paying size=12 * (reservePrice=70 * quoteScale=10_000 + makerFee))
                .action(OrderAction.BID)
                .orderType(OrderType.GTC) // Good-till-Cancel
                .symbol(SYMBOL_LTC_BTC)
                .build());

    log.info("ApiPlaceOrder 1 result: " + future.get());

    // second user places Immediate-or-Cancel Ask (Sell) order of 10LTC
    // he assumes wost rate to sell ~ 161.3 LTC for 1 BTC
    sizeInput = null;
    priceInput = new BigDecimal(Double.toString(1D / 161.2D));
    totalInput = new BigDecimal(Double.toString(10D / 161.2D));

    pricePerLot = convert(priceInput, SYMBOL_LTC_BTC);
    totalPrice = convert(totalInput, SYMBOL_LTC_BTC);
    sizeCalc = totalPrice / pricePerLot;
    log.info("totalPrice {}, pricePerLot {}, sizeCalc {}", totalPrice, pricePerLot, sizeCalc);

    future =
        api.submitCommandAsync(
            ApiPlaceOrder.builder()
                .uid(302L)
                .orderId(5002L)
                .price(pricePerLot) // sell at price 1LTC for price=62 * quoteScale=10_000 = 620_000 satoshi ~ 161,2LTC per 1BTC
                .size(sizeCalc) // order size is 10 lots
                .action(OrderAction.ASK)
                .orderType(OrderType.IOC) // Immediate-or-Cancel
                .symbol(SYMBOL_LTC_BTC)
                .build());

    log.info("ApiPlaceOrder 2 result: " + future.get());

    // request order book
    CompletableFuture<L2MarketData> orderBookFuture1 =
        api.requestOrderBookAsync(SYMBOL_LTC_BTC, 10);
    log.info("ApiOrderBookRequest result: " + orderBookFuture1.get());

    // we can check that users got added successfully and balances adjusted and trade executed
    Future<SingleUserReportResult> u1Report = api.processReport(new SingleUserReportQuery(301L), 0);
    log.info(u1Report.get().toString());

    Future<SingleUserReportResult> u2Report = api.processReport(new SingleUserReportQuery(302L), 0);
    log.info(u2Report.get().toString());

    Future<TotalCurrencyBalanceReportResult> balancesReportBeforeSnapshot =
        api.processReport(new TotalCurrencyBalanceReportQuery(), 0);
    log.info(balancesReportBeforeSnapshot.get().toString());

    // SNAPSHOT
    // save snapshot with ID 123 and shutdown the core
    future = api.submitCommandAsync(ApiPersistState.builder().dumpId(123).build());
    log.info("ApiPersistState result: " + future.get());

    ec.shutdown();

    /* ========= 2ND RUN ========= */
    log.info("Starting from snapshot " + 123);

    // create a new core instance, this time loaded from the snapshot 123, start from sequence 11
    // sequence number needs to be also saved with the last saved snapshot number in DB or something
    conf = testExchangeConfFromSnapshot(123, 0).build();
    ec =
        ExchangeCore.builder()
            .resultsConsumer(new SimpleEventsProcessor(new TestEventHandler(tradeHistory)))
            .exchangeConfiguration(conf)
            .build();
    ec.startup();

    api = ec.getApi();

    // check user reports after snapshot load
    Future<SingleUserReportResult> u1Report2 = api.processReport(new SingleUserReportQuery(301L), 0);
    log.info(u1Report2.get().toString());

    Future<SingleUserReportResult> u2Report2 = api.processReport(new SingleUserReportQuery(302L), 0);
    log.info(u2Report2.get().toString());

    // again request totals report to see user balances and orders
    Future<TotalCurrencyBalanceReportResult> balancesReportAfterSnapshotLoad =
        api.processReport(new TotalCurrencyBalanceReportQuery(), 0);
    log.info(balancesReportAfterSnapshotLoad.get().toString());

    // second user places Immediate-or-Cancel Ask (Sell) order
    // he assumes wost rate to sell 158.7LTC for 1 BTC
    // this time we calculate size from the total price
    sizeInput = new BigDecimal(2L);
    priceInput = new BigDecimal(Double.toString(1D / 158.7D));
    totalInput = null;

    pricePerLot = convert(priceInput, SYMBOL_LTC_BTC);
    totalPrice = convert(sizeInput.multiply(priceInput), SYMBOL_LTC_BTC);
    sizeCalc = sizeInput.toBigInteger().longValue();
    log.info("totalPrice {}, pricePerLot {}, sizeCalc {}", totalPrice, pricePerLot, sizeCalc);

    future =
        api.submitCommandAsync(
            ApiPlaceOrder.builder()
                .uid(302L)
                .orderId(5003L)
                .price(pricePerLot)
                .size(sizeCalc)
                .action(OrderAction.ASK)
                .orderType(OrderType.IOC) // Immediate-or-Cancel
                .symbol(SYMBOL_LTC_BTC)
                .build());

    log.info("ApiPlaceOrder 3 result: " + future.get());

    // request order book again
    CompletableFuture<L2MarketData> orderBookFuture2 =
        api.requestOrderBookAsync(SYMBOL_LTC_BTC, 10);
    log.info("ApiOrderBookRequest result: " + orderBookFuture2.get());

    // check user reports again
    Future<SingleUserReportResult> u1Report3 = api.processReport(new SingleUserReportQuery(301L), 0);
    log.info(u1Report3.get().toString());

    Future<SingleUserReportResult> u2Report3 = api.processReport(new SingleUserReportQuery(302L), 0);
    log.info(u2Report3.get().toString());

    // again request totals report to see user balances and orders
    Future<TotalCurrencyBalanceReportResult> finalBalancesReport =
        api.processReport(new TotalCurrencyBalanceReportQuery(), 0);
    log.info(finalBalancesReport.get().toString());

    // core SHUTDOWN, nothing will happen in the core after this point
    ec.shutdown();

    // print trade history that was recorded from the trade events
    log.info("Number of trades executed: {}", tradeHistory.size());
    for (TradeEvent trade : tradeHistory) {
      log.info(trade.toString());
    }

    // compare balances report before and after the snapshot
    assertEquals(balancesReportBeforeSnapshot.get(), balancesReportAfterSnapshotLoad.get());
  }

  @Test
  public void testConversion_amountWithinRange_shouldConvertCorrectly() throws Exception {
    BigDecimal amount = new BigDecimal(0.0001);
    long expected = 10_000L;
    long actual = convert(amount, SYMBOL_LTC_BTC);
    assertEquals(expected, actual);
  }

  @Test
  public void testConversion_amountBelowRange_shouldThrowException() throws Exception {
    BigDecimal amount = new BigDecimal(0.00009);
    assertThrows(Exception.class, () -> convert(amount, SYMBOL_LTC_BTC));
  }

  private static long convert(BigDecimal amount, int pair) throws Exception {
    switch (pair) {
      case SYMBOL_LTC_BTC: // we will get values in steps of 0.0001 BTC = 10_000 satoshi (defined in the symbol specification)
        BigDecimal baseScale = new BigDecimal(SYMBOL_SPEC_LTC_BTC.baseScaleK); // 1
        BigDecimal quoteScale = new BigDecimal(SYMBOL_SPEC_LTC_BTC.quoteScaleK); // 1
        BigDecimal lotsOfLTCPerLTC = new BigDecimal(UNITS_PER_LTC / SYMBOL_SPEC_LTC_BTC.baseScaleK);
        BigDecimal lotsOfBTCPerBTC = new BigDecimal(UNITS_PER_BTC / SYMBOL_SPEC_LTC_BTC.quoteScaleK);
        BigDecimal low = quoteScale.divide(lotsOfBTCPerBTC); // no rounding
        BigDecimal high = new BigDecimal(Long.MAX_VALUE / SYMBOL_SPEC_LTC_BTC.quoteScaleK);
        log.info("conversion for pair {}: [low {}, high {}, amount {}, lotsOfLTCPerLTC {}, quoteScale {}]", pair, low, high, amount, lotsOfLTCPerLTC, quoteScale);
        if (isWithinRange(amount, low, high)) {
          long ret = amount.multiply(baseScale).divide(quoteScale).toBigInteger().longValue();
          log.info("BTC={} -> satoshi={} * (scale={} / units={})", amount, ret, SYMBOL_SPEC_LTC_BTC.baseScaleK, UNITS_PER_BTC);
          return ret;
        }
        throw new Exception("amount is out of range");
      default:
        log.error("conversion for pair {} is not implemented", pair);
        throw new NotImplementedException();
    }
  }

  private static boolean isWithinRange(BigDecimal amount, BigDecimal low, BigDecimal high) {
    return amount.compareTo(low) >= 0 && amount.compareTo(high) <= 0;
  }

  @AllArgsConstructor
  public static class TestEventHandler implements IEventsHandler {

    private List<TradeEvent> tradeHistory;

    @Override
    public void tradeEvent(TradeEvent tradeEvent) {
      log.info("Trade event: " + tradeEvent);
      tradeHistory.add(tradeEvent);
    }

    @Override
    public void reduceEvent(ReduceEvent reduceEvent) {
      log.info("Reduce event: " + reduceEvent);
    }

    @Override
    public void rejectEvent(RejectEvent rejectEvent) {
      log.info("Reject event: " + rejectEvent);
    }

    @Override
    public void commandResult(ApiCommandResult commandResult) {
      log.info("Command result: " + commandResult);
    }

    @Override
    public void orderBook(OrderBook orderBook) {
      log.info("OrderBook event: " + orderBook);
    }
  }
}

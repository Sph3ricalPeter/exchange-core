package exchange.core2.tests.examples;

import static org.junit.Assert.assertEquals;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.ExchangeCore;
import exchange.core2.core.IEventsHandler;
import exchange.core2.core.IEventsHandler.TradeEvent;
import exchange.core2.core.SimpleEventsProcessor;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.FeeZone;
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

  private static final FeeZone FEE_ZONE_SUB_10K_VOLUME = FeeZone.fromPercent(0.35F, 0.3F);

  private static final long UNITS_PER_BTC = 100_000_000L;
  private static final long UNITS_PER_LTC = 100_000_000L;

  private static final int CURRENCY_BTC = 11;
  private static final int CURRENCY_LTC = 15;

  private static final int SYMBOL_BTC_LTC = 241;

  // symbol specification for the pair LTC/XBT
  // scales are defined to meet our current precision requirement
  private static final CoreSymbolSpecification SYMBOL_SPEC_LTC_BTC =
      CoreSymbolSpecification.builder()
          .symbolId(SYMBOL_BTC_LTC) // symbol id
          .type(SymbolType.CURRENCY_EXCHANGE_PAIR)
          .baseCurrency(CURRENCY_BTC) // base = satoshi (1E-8)
          .quoteCurrency(CURRENCY_LTC) // quote = litoshi (1E-8)
          .baseScaleK(1_000_000L) // 1 lot = 1M satoshi (0.01 BTC)
          .quoteScaleK(10_000L) // 1 price step = 10K litoshi
          .takerBaseFee(1900L) // taker fee 1900 litoshi per 1 lot
          .makerBaseFee(700L) // maker fee 700 litoshi per 1 lot
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
                .loggingLevels(EnumSet.of(LoggingLevel.LOGGING_WARNINGS))
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
    u1Accounts.put(CURRENCY_BTC, 0);
    u1Accounts.put(CURRENCY_LTC, 0);
    userAccounts.put(301L, u1Accounts);

    IntLongHashMap u2Accounts = new IntLongHashMap();
    u2Accounts.put(CURRENCY_BTC, 0);
    u2Accounts.put(CURRENCY_LTC, 0);
    userAccounts.put(302L, u2Accounts);

    future =
        api.submitBinaryDataAsync(
            new BatchAddAccountsCommand(userAccounts, FEE_ZONE_SUB_10K_VOLUME));
    log.info("BatchAddAccountsCommand result: " + future.get());

    // DEPOSITS
    // first user deposits 20 LTC
    future =
        api.submitCommandAsync(
            ApiAdjustUserBalance.builder()
                .uid(301L)
                .currency(CURRENCY_LTC)
                .amount(20 * UNITS_PER_LTC) // in satoshi
                .transactionId(
                    2001L) // transaction id has to be > 1002 because batchAddUsers balance
                // adjustment sets last transaction id to 1000, needs to be replaced with
                // a sequence generated value
                .build());

    log.info("ApiAdjustUserBalance 1 result: " + future.get());

    // second user deposits 0.12 BTC
    future =
        api.submitCommandAsync(
            ApiAdjustUserBalance.builder()
                .uid(302L)
                .currency(CURRENCY_BTC)
                .amount((long) (0.12f * UNITS_PER_BTC)) // in litoshi
                .transactionId(2002L)
                .build());

    log.info("ApiAdjustUserBalance 2 result: " + future.get());

    // ORDERS
    // first user places Good-till-Cancel Bid order
    // he assumes BTCLTC exchange rate 154 LTC for 1 BTC
    // bid price for 1 lot (0.01BTC) is 1.54 LTC => 1_5400_0000 litoshi => 10K * 15_400 (in price
    // steps)
    future =
        api.submitCommandAsync(
            ApiPlaceOrder.builder()
                .uid(301L)
                .orderId(5001L)
                .price(15_400L)
                .reservePrice(
                    15_600L) // can move bid order up to the 1.56 LTC, without replacing it
                .size(12L) // order size is 12 lots
                .action(OrderAction.BID)
                .orderType(OrderType.GTC) // Good-till-Cancel
                .symbol(SYMBOL_BTC_LTC)
                .build());

    log.info("ApiPlaceOrder 1 result: " + future.get());

    // second user places Immediate-or-Cancel Ask (Sell) order
    // he assumes wost rate to sell 152.5 LTC for 1 BTC
    future =
        api.submitCommandAsync(
            ApiPlaceOrder.builder()
                .uid(302L)
                .orderId(5002L)
                .price(15_250L)
                .size(10L) // order size is 10 lots
                .action(OrderAction.ASK)
                .orderType(OrderType.IOC) // Immediate-or-Cancel
                .symbol(SYMBOL_BTC_LTC)
                .build());

    log.info("ApiPlaceOrder 2 result: " + future.get());

    // request order book
    CompletableFuture<L2MarketData> orderBookFuture1 =
        api.requestOrderBookAsync(SYMBOL_BTC_LTC, 10);
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
    Future<SingleUserReportResult> u1Report2 =
        api.processReport(new SingleUserReportQuery(301L), 0);
    log.info(u1Report2.get().toString());

    Future<SingleUserReportResult> u2Report2 =
        api.processReport(new SingleUserReportQuery(302L), 0);
    log.info(u2Report2.get().toString());

    // again request totals report to see user balances and orders
    Future<TotalCurrencyBalanceReportResult> balancesReportAfterSnapshotLoad =
        api.processReport(new TotalCurrencyBalanceReportQuery(), 0);
    log.info(balancesReportAfterSnapshotLoad.get().toString());

    // second user places Immediate-or-Cancel Ask (Sell) order again
    // he assumes wost rate to sell 152.5 LTC for 1 BTC
    future =
        api.submitCommandAsync(
            ApiPlaceOrder.builder()
                .uid(302L)
                .orderId(5002L)
                .price(15_250L)
                .size(2L) // order size is 2 lots
                .action(OrderAction.ASK)
                .orderType(OrderType.IOC) // Immediate-or-Cancel
                .symbol(SYMBOL_BTC_LTC)
                .build());

    log.info("ApiPlaceOrder 3 result: " + future.get());

    // request order book again
    CompletableFuture<L2MarketData> orderBookFuture2 =
        api.requestOrderBookAsync(SYMBOL_BTC_LTC, 10);
    log.info("ApiOrderBookRequest result: " + orderBookFuture2.get());

    // check user reports again
    Future<SingleUserReportResult> u1Report3 =
        api.processReport(new SingleUserReportQuery(301L), 0);
    log.info(u1Report3.get().toString());

    Future<SingleUserReportResult> u2Report3 =
        api.processReport(new SingleUserReportQuery(302L), 0);
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

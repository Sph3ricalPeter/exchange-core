package exchange.core2.tests.examples;

import static org.junit.Assert.assertEquals;

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

  private static final int CURRENCY_EUR = 10;
  // XBT is equivalent for BTC
  private static final int CURRENCY_XBT = 11;
  private static final int CURRENCY_LTC = 15;

  private static final int SYMBOL_XBT_EUR = 240;
  private static final int SYMBOL_LTC_XBT = 241;

  // symbol specification for the pair XBT/EUR
  private static final CoreSymbolSpecification SYMBOL_SPEC_XBT_EUR =
      CoreSymbolSpecification.builder()
          .symbolId(SYMBOL_XBT_EUR) // symbol id
          .type(SymbolType.CURRENCY_EXCHANGE_PAIR)
          .baseCurrency(CURRENCY_XBT) // base = satoshi (1E-8)
          .quoteCurrency(CURRENCY_EUR) // quote = cents (1E-2)
          .baseScaleK(1_000_000L) // 1 lot = 1M satoshi (0.01 BTC)
          .quoteScaleK(100L) // 1 price step = 100 cents (1 EUR), can buy BTC with 1 EUR steps
          .takerFee(1L) // taker fee 1 cent per 1 lot
          .makerFee(3L) // maker fee 3 cents per 1 lot
          .build();

  // symbol specification for the pair LTC/XBT
  private static final CoreSymbolSpecification SYMBOL_SPEC_LTC_XBT =
      CoreSymbolSpecification.builder()
          .symbolId(SYMBOL_LTC_XBT) // symbol id
          .type(SymbolType.CURRENCY_EXCHANGE_PAIR)
          .baseCurrency(CURRENCY_LTC) // base = satoshi (1E-8)
          .quoteCurrency(CURRENCY_XBT) // quote = litoshi (1E-8)
          .baseScaleK(10_000L) // 1 price step = 10K litoshi
          .quoteScaleK(1_000_000L) // 1 lot = 1M satoshi (0.01 BTC)
          .takerFee(100L) // taker fee 100 satoshi per 1 lot (0.01%)
          .makerFee(300L) // maker fee 300 satoshi per 1 lot (0.03%)
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
      throws ExecutionException, InterruptedException {

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
    symbols.add(SYMBOL_SPEC_XBT_EUR);
    symbols.add(SYMBOL_SPEC_LTC_XBT);

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
    u1Accounts.put(CURRENCY_XBT, 0);
    u1Accounts.put(CURRENCY_LTC, 0);
    userAccounts.put(301L, u1Accounts);

    IntLongHashMap u2Accounts = new IntLongHashMap();
    u2Accounts.put(CURRENCY_EUR, 0);
    u2Accounts.put(CURRENCY_XBT, 0);
    u2Accounts.put(CURRENCY_LTC, 0);
    userAccounts.put(302L, u2Accounts);

    future = api.submitBinaryDataAsync(new BatchAddAccountsCommand(userAccounts));
    log.info("BatchAddAccountsCommand result: " + future.get());

    // DEPOSITS
    // first user deposits 20 LTC
    future =
        api.submitCommandAsync(
            ApiAdjustUserBalance.builder()
                .uid(301L)
                .currency(CURRENCY_XBT)
                .amount(10_000_000L)
                .transactionId(2001L) // transaction id has to be > 1002 because batchAddUsers balance adjustment sets last transaction id to 1000, needs to be replaced with a sequence generated value
                .build());

    log.info("ApiAdjustUserBalance 1 result: " + future.get());

    // second user deposits 0.10 BTC
    future =
        api.submitCommandAsync(
            ApiAdjustUserBalance.builder()
                .uid(302L)
                .currency(CURRENCY_LTC)
                .amount(2_000_000_000L)
                .transactionId(2002L)
                .build());

    log.info("ApiAdjustUserBalance 2 result: " + future.get());

    // we can check that users got added successfully and balances adjusted
    Future<SingleUserReportResult> u1Report = api.processReport(new SingleUserReportQuery(301L), 0);
    log.info(u1Report.get().toString());

    Future<SingleUserReportResult> u2Report = api.processReport(new SingleUserReportQuery(302L), 0);
    log.info(u2Report.get().toString());

    // ORDERS
    // user creates a buy order for 12 lots of LTC at a price of 64 satoshi per 1 lot of LTC
    // TODO: NSF error, why? Price should be in BTC, as LTC is base currency
    future =
        api.submitCommandAsync(
            ApiPlaceOrder.builder()
                .uid(301L)
                .orderId(5001L)
                .price(64L) // pay 64 satoshi per 10_000 litoshi (ratio ~ 1BTC : 156.3LTC)
                .reservePrice(
                    70L) // can move bid order up to the 1.56 LTC, without replacing it
                .size(12L) // order size is 12 lots (12 * 0.0001 LTC = 0.0012 LTC total)
                .action(OrderAction.BID)
                .orderType(OrderType.GTC) // Good-till-Cancel
                .symbol(SYMBOL_LTC_XBT)
                .build());

    log.info("ApiPlaceOrder 1 result: " + future.get());

    // second user places Immediate-or-Cancel Ask (Sell) order
    // he assumes wost rate to sell ~ 161.3 LTC for 1 BTC
    future =
        api.submitCommandAsync(
            ApiPlaceOrder.builder()
                .uid(302L)
                .orderId(5002L)
                .price(62L) // sell at 62 satoshi per 10_000 litoshi
                .size(10L) // order size is 10 lots
                .action(OrderAction.ASK)
                .orderType(OrderType.IOC) // Immediate-or-Cancel
                .symbol(SYMBOL_LTC_XBT)
                .build());

    log.info("ApiPlaceOrder 2 result: " + future.get());

    // request order book
    CompletableFuture<L2MarketData> orderBookFuture1 =
        api.requestOrderBookAsync(SYMBOL_LTC_XBT, 10);
    log.info("ApiOrderBookRequest result: " + orderBookFuture1.get());

    // get totals report to see how the trade affected user balances and open orders
    Future<TotalCurrencyBalanceReportResult> balancesReport0 =
        api.processReport(new TotalCurrencyBalanceReportQuery(), 0);
    log.info(balancesReport0.get().toString());

    // SNAPSHOT
    // save snapshot with ID 123 and shutdown the core
    future = api.submitCommandAsync(ApiPersistState.builder().dumpId(123).build());
    log.info("ApiPersistState result: " + future.get());

    ec.shutdown();

    /* ========= 2ND RUN ========= */
    log.info("Starting from snapshot " + 123);

    // create a new core instance, this time loaded from the snapshot 123, start from sequence 11
    // sequence number needs to be also saved with the last saved snapshot number in DB or something
    conf = testExchangeConfFromSnapshot(123, 11).build();
    ec =
        ExchangeCore.builder()
            .resultsConsumer(new SimpleEventsProcessor(new TestEventHandler(tradeHistory)))
            .exchangeConfiguration(conf)
            .build();
    ec.startup();

    api = ec.getApi();

    // second user places Immediate-or-Cancel Ask (Sell) order
    // he assumes wost rate to sell 152.5 LTC for 1 BTC
    future =
        api.submitCommandAsync(
            ApiPlaceOrder.builder()
                .uid(302L)
                .orderId(5003L)
                .price(63L)
                .size(2L) // order size is 2 lots
                .action(OrderAction.ASK)
                .orderType(OrderType.IOC) // Immediate-or-Cancel
                .symbol(SYMBOL_LTC_XBT)
                .build());

    log.info("ApiPlaceOrder 3 result: " + future.get());

    // request order book again
    CompletableFuture<L2MarketData> orderBookFuture2 =
        api.requestOrderBookAsync(SYMBOL_LTC_XBT, 10);
    log.info("ApiOrderBookRequest result: " + orderBookFuture2.get());

    // again request totals report to see user balances and orders
    Future<TotalCurrencyBalanceReportResult> balancesReport1 =
        api.processReport(new TotalCurrencyBalanceReportQuery(), 0);
    log.info(balancesReport1.get().toString());

    // core SHUTDOWN, nothing will happen in the core after this point
    ec.shutdown();

    // print trade history that was recorded from the trade events
    log.info(String.format("Number of trades executed: {}", tradeHistory.size()));
    for (TradeEvent trade : tradeHistory) {
      log.info(trade.toString());
    }

    // compare balances report before and after the snapshot
    assertEquals(balancesReport0.get(), balancesReport1.get());
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

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
import exchange.core2.core.common.api.ApiAddUser;
import exchange.core2.core.common.api.ApiAdjustUserBalance;
import exchange.core2.core.common.api.ApiPersistState;
import exchange.core2.core.common.api.ApiPlaceOrder;
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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;

@Slf4j
public class ITCoreSnapshottingExample {

  private static final String EXCHANGE_ID = "TEST_EXCHANGE";
  private static final int CURRENCY_XBT = 11;
  private static final int CURRENCY_LTC = 15;
  private static final int SYMBOL_XBT_LTC = 241;

  private final List<TradeEvent> trades = new ArrayList<>();

  public ExchangeConfigurationBuilder testExchangeConfCleanBuilder() {
    return ExchangeConfiguration.builder()
        .ordersProcessingCfg(OrdersProcessingConfiguration.DEFAULT)
        .initStateCfg(InitialStateConfiguration.cleanStart(EXCHANGE_ID))
        .performanceCfg(PerformanceConfiguration.DEFAULT) // balanced perf. config
        .reportsQueriesCfg(
            ReportsQueriesConfiguration
                .DEFAULT) // no idea how to use reports, should be possible to write custom reports
        // but how?
        .loggingCfg(
            LoggingConfiguration.builder()
                .loggingLevels(EnumSet.of(LoggingLevel.LOGGING_WARNINGS))
                .build())
        .serializationCfg(
            SerializationConfiguration
                .DISK_SNAPSHOT_ONLY_REPLACE); // default disk journaling to the `dumps` folder,
    // replace files for convenience
  }

  public ExchangeConfigurationBuilder testExchangeConfFromSnapshot(long snapshotId, long baseSeq) {
    return testExchangeConfCleanBuilder()
        .initStateCfg(InitialStateConfiguration.fromSnapshotOnly(EXCHANGE_ID, snapshotId, baseSeq));
  }

  @Test
  public void testCleanStartInitShutdownThenStartFromSnapshot_balanceReportsShouldEqual()
      throws ExecutionException, InterruptedException {
    /* ========= 1ST RUN ========= */
    System.out.println("Starting clean");
    ExchangeConfiguration conf = testExchangeConfCleanBuilder().build();
    ExchangeCore ec =
        ExchangeCore.builder()
            .resultsConsumer(new SimpleEventsProcessor(new TestEventHandler(trades)))
            .exchangeConfiguration(conf)
            .build();
    ec.startup();

    ExchangeApi api = ec.getApi();

    // do stuff
    // create symbol specification and publish it
    CoreSymbolSpecification symbolSpecXbtLtc =
        CoreSymbolSpecification.builder()
            .symbolId(SYMBOL_XBT_LTC) // symbol id
            .type(SymbolType.CURRENCY_EXCHANGE_PAIR)
            .baseCurrency(CURRENCY_XBT) // base = satoshi (1E-8)
            .quoteCurrency(CURRENCY_LTC) // quote = litoshi (1E-8)
            .baseScaleK(1_000_000L) // 1 lot = 1M satoshi (0.01 BTC)
            .quoteScaleK(10_000L) // 1 price step = 10K litoshi
            .takerFee(1900L) // taker fee 1900 litoshi per 1 lot
            .makerFee(700L) // maker fee 700 litoshi per 1 lot
            .build();

    Future<CommandResultCode> future =
        api.submitBinaryDataAsync(new BatchAddSymbolsCommand(symbolSpecXbtLtc));
    System.out.println("BatchAddSymbolsCommand result: " + future.get());

    // create user uid=301
    future = api.submitCommandAsync(ApiAddUser.builder().uid(301L).build());
    System.out.println("ApiAddUser 1 result: " + future.get());

    // create user uid=302
    future = api.submitCommandAsync(ApiAddUser.builder().uid(302L).build());
    System.out.println("ApiAddUser 2 result: " + future.get());

    // first user deposits 20 LTC
    future =
        api.submitCommandAsync(
            ApiAdjustUserBalance.builder()
                .uid(301L)
                .currency(CURRENCY_LTC)
                .amount(2_000_000_000L)
                .transactionId(1L)
                .build());

    System.out.println("ApiAdjustUserBalance 1 result: " + future.get());

    // second user deposits 0.10 BTC
    future =
        api.submitCommandAsync(
            ApiAdjustUserBalance.builder()
                .uid(302L)
                .currency(CURRENCY_XBT)
                .amount(10_000_000L)
                .transactionId(2L)
                .build());

    System.out.println("ApiAdjustUserBalance 2 result: " + future.get());

    // first user places Good-till-Cancel Bid order
    // he assumes BTCLTC exchange rate 154 LTC for 1 BTC
    // bid price for 1 lot (0.01BTC) is 1.54 LTC => 1_5400_0000 litoshi => 10K * 15_400 (in price
    // steps)

    // BTC: 1 lot = 0.01BTC = 1_000_000 sats
    // LTC: 1 lot = 0.0001LTC = 10_000 lits
    // price per 0.01BTC is 15_400L lits = 1.54LTC, so for 1BTC they need to pay 154LTC

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
                .symbol(SYMBOL_XBT_LTC)
                .build());

    System.out.println("ApiPlaceOrder 1 result: " + future.get());

    Future<SingleUserReportResult> report0 = api.processReport(new SingleUserReportQuery(301), 0);
    System.out.println(report0.get());

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
                .symbol(SYMBOL_XBT_LTC)
                .build());

    System.out.println("ApiPlaceOrder 2 result: " + future.get());

    Future<SingleUserReportResult> report1 = api.processReport(new SingleUserReportQuery(302), 0);
    System.out.println(report1.get());

    // request order book
    CompletableFuture<L2MarketData> orderBookFuture1 =
        api.requestOrderBookAsync(SYMBOL_XBT_LTC, 10);
    System.out.println("ApiOrderBookRequest result: " + orderBookFuture1.get());

    Future<TotalCurrencyBalanceReportResult> balancesReport0 =
        api.processReport(new TotalCurrencyBalanceReportQuery(), 0);
    System.out.println(balancesReport0.get());

    future = api.submitCommandAsync(ApiPersistState.builder().dumpId(123).build());
    System.out.println("ApiPersistState result: " + future.get());

    ec.shutdown();

    /* ========= 2ND RUN ========= */
    System.out.println("Starting from snapshot " + 123);
    conf = testExchangeConfFromSnapshot(123, 11).build();
    ec =
        ExchangeCore.builder()
            .resultsConsumer(new SimpleEventsProcessor(new TestEventHandler(trades)))
            .exchangeConfiguration(conf)
            .build();
    ec.startup();

    api = ec.getApi();

    // do stuff
    // second user deposits 0.10 BTC, test should fail
    /*future =
    api.submitCommandAsync(
        ApiAdjustUserBalance.builder()
            .uid(302L)
            .currency(CURRENCY_XBT)
            .amount(10_000_000L)
            .transactionId(10L)
            .build());

    System.out.println("ApiAdjustUserBalance 2 result: " + future.get());*/

    // second user places Immediate-or-Cancel Ask (Sell) order
    // he assumes wost rate to sell 152.5 LTC for 1 BTC
    future =
        api.submitCommandAsync(
            ApiPlaceOrder.builder()
                .uid(302L)
                .orderId(5003L)
                .price(15_250L)
                .size(2L) // order size is 2 lots
                .action(OrderAction.ASK)
                .orderType(OrderType.IOC) // Immediate-or-Cancel
                .symbol(SYMBOL_XBT_LTC)
                .build());

    System.out.println("ApiPlaceOrder 3 result: " + future.get());

    Future<SingleUserReportResult> report11 = api.processReport(new SingleUserReportQuery(301), 0);
    System.out.println(report11.get());

    Future<SingleUserReportResult> report12 = api.processReport(new SingleUserReportQuery(302), 0);
    System.out.println(report12.get());

    // request order book
    CompletableFuture<L2MarketData> orderBookFuture2 =
        api.requestOrderBookAsync(SYMBOL_XBT_LTC, 10);
    System.out.println("ApiOrderBookRequest result: " + orderBookFuture2.get());

    Future<TotalCurrencyBalanceReportResult> balancesReport1 =
        api.processReport(new TotalCurrencyBalanceReportQuery(), 0);
    System.out.println(balancesReport1.get());

    ec.shutdown();

    System.out.println(trades.size());
    for (TradeEvent trade : trades) {
      System.out.println(trade);
    }

    assertEquals(balancesReport0.get(), balancesReport1.get());
  }

  @AllArgsConstructor
  public static class TestEventHandler implements IEventsHandler {

    private List<TradeEvent> trades;

    @Override
    public void tradeEvent(TradeEvent tradeEvent) {
      System.out.println("Trade event: " + tradeEvent);
      trades.add(tradeEvent);
    }

    @Override
    public void reduceEvent(ReduceEvent reduceEvent) {
      System.out.println("Reduce event: " + reduceEvent);
    }

    @Override
    public void rejectEvent(RejectEvent rejectEvent) {
      System.out.println("Reject event: " + rejectEvent);
    }

    @Override
    public void commandResult(ApiCommandResult commandResult) {
      System.out.println("Command result: " + commandResult);
    }

    @Override
    public void orderBook(OrderBook orderBook) {
      System.out.println("OrderBook event: " + orderBook);
    }
  }
}

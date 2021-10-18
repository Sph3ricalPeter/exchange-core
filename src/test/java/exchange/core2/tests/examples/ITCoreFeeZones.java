package exchange.core2.tests.examples;

import static org.junit.Assert.assertEquals;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.ExchangeCore;
import exchange.core2.core.IEventsHandler;
import exchange.core2.core.SimpleEventsProcessor;
import exchange.core2.core.common.FeeZone;
import exchange.core2.core.common.api.binary.BatchAddAccountsCommand;
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
import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.junit.Test;

@Slf4j
public class ITCoreFeeZones {

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

    // ACCOUNTS & BALANCES
    // we can use batch add users to efficiently init all users and their balance
    LongObjectHashMap<IntLongHashMap> userAccounts = new LongObjectHashMap<>();
    userAccounts.put(301L, new IntLongHashMap());
    userAccounts.put(302L, new IntLongHashMap());

    // add accounts with initial fee zones
    FeeZone expected = FeeZone.fromPercent(0.35f, 0.3f);

    CompletableFuture<CommandResultCode> future =
        api.submitBinaryDataAsync(new BatchAddAccountsCommand(userAccounts, expected));
    log.info("BatchAddAccountsCommand result: " + future.get());

    Future<SingleUserReportResult> report1 = api.processReport(new SingleUserReportQuery(301), 0);
    SingleUserReportResult report1Result = report1.get();
    FeeZone actual = report1Result.getFeeZone();

    assertEquals(expected, actual);
    log.info(report1.get().toString());

    System.out.println(report1.get());

    // adjust fee zone for user 1
    expected = FeeZone.fromPercent(0.4f, 0.5f);

    LongObjectHashMap<FeeZone> feeZoneUpdate = new LongObjectHashMap<>();
    feeZoneUpdate.put(301, expected);

    future = api.submitBinaryDataAsync(new BatchUpdateUserFeeZones(feeZoneUpdate));
    log.info("BatchUpdateUserFeeZones result: " + future.get());

    Future<SingleUserReportResult> report2 = api.processReport(new SingleUserReportQuery(301), 0);
    SingleUserReportResult report2Result = report2.get();
    actual = report2Result.getFeeZone();

    assertEquals(expected, actual);
    log.info(report2.get().toString());

    exchangeCore.shutdown();
  }
}

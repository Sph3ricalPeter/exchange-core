package exchange.core2.tests.examples;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.ExchangeCore;
import exchange.core2.core.IEventsHandler;
import exchange.core2.core.SimpleEventsProcessor;
import exchange.core2.core.common.*;
import exchange.core2.core.common.api.*;
import exchange.core2.core.common.api.binary.BatchAddSymbolsCommand;
import exchange.core2.core.common.api.reports.SingleUserReportQuery;
import exchange.core2.core.common.api.reports.SingleUserReportResult;
import exchange.core2.core.common.api.reports.TotalCurrencyBalanceReportQuery;
import exchange.core2.core.common.api.reports.TotalCurrencyBalanceReportResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.ExchangeConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

@Slf4j
public class ITCoreExample {

  @Test
  public void sampleTest() throws Exception {

    // simple async events handler
    SimpleEventsProcessor eventsProcessor =
        new SimpleEventsProcessor(
            new IEventsHandler() {
              @Override
              public void tradeEvent(TradeEvent tradeEvent) {
                System.out.println("Trade event: " + tradeEvent);
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
            });

    // default exchange configuration
    ExchangeConfiguration conf = ExchangeConfiguration.defaultBuilder().build();

    // build exchange core
    ExchangeCore exchangeCore =
        ExchangeCore.builder().resultsConsumer(eventsProcessor).exchangeConfiguration(conf).build();

    // start up disruptor threads
    exchangeCore.startup();

    // get exchange API for publishing commands
    ExchangeApi api = exchangeCore.getApi();

    // currency code constants
    // ? probably specific to the exchange
    final int currencyCodeXbt = 11;
    final int currencyCodeLtc = 15;

    // symbol constants
    // ? symbol is currency pair, futures contract or option
    // ? XBT = BTC (XBT applies to ISO 4217 norm for non-national currencies)
    final int symbolXbtLtcId = 241;

    // create symbol specification and publish it
    // ? we define a currency pair between XBT and LTC here, XBT being the base currency and LTC
    // quote currency
    // ? we also define the base scales, probably being the minimum steps in price (increments of
    // 0.01 for BTC = 1M satoshi)
    CoreSymbolSpecification symbolSpecXbtLtc =
        CoreSymbolSpecification.builder()
            .symbolId(symbolXbtLtcId) // symbol id
            .type(SymbolType.CURRENCY_EXCHANGE_PAIR)
            .baseCurrency(currencyCodeXbt) // base = satoshi (1E-8)
            .quoteCurrency(currencyCodeLtc) // quote = litoshi (1E-8)
            .baseScaleK(1_000_000L) // 1 lot = 1M satoshi (0.01 BTC)
            .quoteScaleK(10_000L) // 1 price step = 10K litoshi
            .takerFee(1900L) // taker fee 1900 litoshi per 1 lot
            .makerFee(700L) // maker fee 700 litoshi per 1 lot
            .build();

    // ? future object for async results
    Future<CommandResultCode> future;

    // ? add symbol via exchange API
    future = api.submitBinaryDataAsync(new BatchAddSymbolsCommand(symbolSpecXbtLtc));
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
                .currency(currencyCodeLtc)
                .amount(2_000_000_000L)
                .transactionId(1L)
                .build());

    System.out.println("ApiAdjustUserBalance 1 result: " + future.get());

    // second user deposits 0.10 BTC
    future =
        api.submitCommandAsync(
            ApiAdjustUserBalance.builder()
                .uid(302L)
                .currency(currencyCodeXbt)
                .amount(10_000_000L)
                .transactionId(2L)
                .build());

    System.out.println("ApiAdjustUserBalance 2 result: " + future.get());

    // first user places Good-till-Cancel Bid order
    // he assumes BTCLTC exchange rate 154 LTC for 1 BTC
    // bid price for 1 lot (0.01BTC) is 1.54 LTC => 1_5400_0000 litoshi => 10K * 15_400 (in price
    // steps)
    // ? user 1 placed a limit offer = good-till-cancel
    // ? Pair base=XBT/quote=LTC, bid & ask price both relate to quote currency
    // ? bid price = amount of quote currency that is offered for 1 unit of base currency (how much
    // LTC will I get for 1 BTC?)
    // ? ask price = amount of quote currency that is asked for 1 unit of base currency (how much
    // LTC do I need to pay for 1 BTC?)
    // ? so here user 1 is placing a limit order, bidding 154LTC for 1BTC, so 18.72LTC for 0.12BTC
    future =
        api.submitCommandAsync(
            ApiPlaceOrder.builder()
                .uid(301L)
                .orderId(5001L)
                .price(15_400L)
                .reservePrice(
                    15_600L) // can move bid order up to the 1.56 LTC, without replacing it
                .size(
                    12L) // order size is 12 lots ? so 0.12BTC to maximum of 18.48LTC (with no fees)
                .action(OrderAction.BID)
                .orderType(OrderType.GTC) // Good-till-Cancel
                .symbol(symbolXbtLtcId)
                .build());

    System.out.println("ApiPlaceOrder 1 result: " + future.get());

    // second user places Immediate-or-Cancel Ask (Sell) order
    // he assumes wost rate to sell 152.5 LTC for 1 BTC
    // ? here user 2 is placing an immediate order, asking 152.5LTC for 1 BTC using 10 lots, so
    // 15.25LTC for 0.1BTC (1 lot = 0.01BTC)
    future =
        api.submitCommandAsync(
            ApiPlaceOrder.builder()
                .uid(302L)
                .orderId(5002L)
                .price(15_250L)
                .size(10L) // order size is 10 lots
                .action(OrderAction.ASK)
                .orderType(OrderType.IOC) // Immediate-or-Cancel
                .symbol(symbolXbtLtcId)
                .build());

    System.out.println("ApiPlaceOrder 2 result: " + future.get());

    // request order book
    // ? this is probably a request for an order book for the given symbol (in this case a currency
    // trading pair) with last 10 orders
    CompletableFuture<L2MarketData> orderBookFuture = api.requestOrderBookAsync(symbolXbtLtcId, 10);
    System.out.println("ApiOrderBookRequest result: " + orderBookFuture.get());

    // first user moves remaining order to price 1.53 LTC
    future =
        api.submitCommandAsync(
            ApiMoveOrder.builder()
                .uid(301L)
                .orderId(5001L)
                .newPrice(15_300L)
                .symbol(symbolXbtLtcId)
                .build());

    System.out.println("ApiMoveOrder 2 result: " + future.get());

    // first user cancel remaining order
    future =
        api.submitCommandAsync(
            ApiCancelOrder.builder().uid(301L).orderId(5001L).symbol(symbolXbtLtcId).build());

    System.out.println("ApiCancelOrder 2 result: " + future.get());

    // check balances
    Future<SingleUserReportResult> report1 = api.processReport(new SingleUserReportQuery(301), 0);
    System.out.println("SingleUserReportQuery 1 accounts: " + report1.get().getAccounts());

    Future<SingleUserReportResult> report2 = api.processReport(new SingleUserReportQuery(302), 0);
    System.out.println("SingleUserReportQuery 2 accounts: " + report2.get().getAccounts());

    // first user withdraws 0.10 BTC
    future =
        api.submitCommandAsync(
            ApiAdjustUserBalance.builder()
                .uid(301L)
                .currency(currencyCodeXbt)
                .amount(-10_000_000L)
                .transactionId(3L)
                .build());

    System.out.println("ApiAdjustUserBalance 1 result: " + future.get());

    // check fees collected
    Future<TotalCurrencyBalanceReportResult> totalsReport =
        api.processReport(new TotalCurrencyBalanceReportQuery(), 0);
    System.out.println("LTC fees collected: " + totalsReport.get().getFees().get(currencyCodeLtc));
  }
}

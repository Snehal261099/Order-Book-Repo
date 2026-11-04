package com.example.orderbook;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
public class Main {
        public static void main(String[] args) throws Exception {

            // Step 1: Create the actual order book
            OrderBook orderBook = new InMemoryOrderBook();

            // Step 2: Create a publisher to receive executed trades
            AsyncOrderBookService service =
                    new AsyncOrderBookService(
                            orderBook,
                            trade -> System.out.println(
                                    "TRADE EXECUTED: " + trade
                            )
                    );

            try {
                // Step 3: Submit a SELL order
                OrderRequest sellOrder = new OrderRequest(
                        "SELL-101",
                        Side.ASK,
                        new BigDecimal("100"),
                        10,
                        Instant.now(),
                        "CLIENT-A",
                        TimeInForce.GTC
                );

                AsyncOrderBookService.Submission sellSubmission =
                        service.submitAdd(sellOrder);

                // Step 4: Check whether the request was accepted
                System.out.println(
                        "SELL request accepted: "
                                + sellSubmission.receivedAck().get()
                );

                // Step 5: Wait until SELL order is actually processed
                List<ExecutedTrade> sellTrades =
                        sellSubmission.processingResult().get();

                System.out.println("SELL processing completed");
                System.out.println("Trades: " + sellTrades);


                // Step 6: Submit a BUY order
                OrderRequest buyOrder = new OrderRequest(
                        "BUY-101",
                        Side.BID,
                        new BigDecimal("105"),
                        11,
                        Instant.now(),
                        "CLIENT-B",
                        TimeInForce.GTC
                );

                AsyncOrderBookService.Submission buySubmission =
                        service.submitAdd(buyOrder);

                System.out.println(
                        "BUY request accepted: "
                                + buySubmission.receivedAck().get()
                );

                // Step 7: Wait until matching is completed
                List<ExecutedTrade> executedTrades =
                        buySubmission.processingResult().get();

                System.out.println("BUY processing completed");
                System.out.println("Executed trades: " + executedTrades);


                // Step 8: Query the remaining SELL order
                var remainingSellOrder = service.query(
                        () -> orderBook.orderById("SELL-101")
                ).get();

                System.out.println(
                        "Remaining SELL order: " + remainingSellOrder
                );
                var currentActiveOrders =
                        service.query(() -> orderBook.orderById("BUY-101")).get();

                System.out.println(
                        "Active orders: " + currentActiveOrders
                );

            } finally {
                // Step 9: Shut down executors
                service.close();
            }
        }
}
package com.example.orderbook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryOrderBookTest {

    private InMemoryOrderBook book;

    @BeforeEach
    void setUp() {
        book = new InMemoryOrderBook();
    }

    private Instant t(int seconds) {
        return Instant.parse("2026-01-01T00:00:00Z")
                .plusSeconds(seconds);
    }

    private OrderRequest order(
            String id, Side side, String price,
            long qty, int time) {

        return new OrderRequest(
                id, side, new BigDecimal(price), qty, t(time)
        );
    }

    // ==================================================
    // 1. EXISTING FUNCTIONAL TEST CASES
    // ==================================================

    @Test
    void priceAndTimePriorityAndQueries() {
        book.add(order("b1", Side.BID, "50", 400, 1));
        book.add(order("b2", Side.BID, "50", 300, 2));
        book.add(order("b3", Side.BID, "51", 100, 3));
        book.add(order("a1", Side.ASK, "55", 400, 4));

        assertEquals(new BigDecimal("51"),
                book.topPrice(Side.BID).orElseThrow());

        assertEquals(new BigDecimal("50"),
                book.bottomPrice(Side.BID).orElseThrow());

        assertEquals(
                List.of(new BigDecimal("51"), new BigDecimal("50")),
                book.priceLevels(Side.BID)
        );

        assertEquals(
                List.of("b1", "b2"),
                book.ordersAtPrice(Side.BID, new BigDecimal("50"))
                        .stream().map(OrderSnapshot::id).toList()
        );

        assertEquals(2, book.priceLevelCount(Side.BID));
        assertFalse(book.isCrossed());
    }

    @Test
    void amendPriceResetsPriorityButReductionRetainsPriority() {
        book.add(order("1", Side.BID, "50", 100, 1));
        book.add(order("2", Side.BID, "50", 100, 2));

        book.amend("1", new BigDecimal("50"), 150, t(3));

        assertEquals(
                List.of("2", "1"),
                book.ordersAtPrice(Side.BID, new BigDecimal("50"))
                        .stream().map(OrderSnapshot::id).toList()
        );

        book.amend("2", new BigDecimal("50"), 50, t(4));

        assertEquals(
                List.of("2", "1"),
                book.ordersAtPrice(Side.BID, new BigDecimal("50"))
                        .stream().map(OrderSnapshot::id).toList()
        );
    }

    @Test
    void cancellationAndLastTransactionSurviveRemoval() {
        book.add(order("1", Side.BID, "50", 100, 1));

        book.remove("1", t(2));

        assertTrue(book.orderById("1").isEmpty());

        assertEquals(
                OrderTransaction.Action.REMOVE,
                book.lastTransaction("1").orElseThrow().action()
        );

        assertEquals(0, book.orderCount(Side.BID));
    }

    @Test
    void crossedBookAndMatchingConsumePriceTimePriority() {
        book.add(order("b1", Side.BID, "51", 100, 1));
        book.add(order("b2", Side.BID, "50", 100, 2));

        List<ExecutedTrade> fills =
                book.add(order("s1", Side.ASK, "50", 150, 3));

        assertEquals(2, fills.size());

        assertEquals("b1", fills.get(0).makerOrderId());
        assertEquals(100, fills.get(0).quantity());

        assertEquals("b2", fills.get(1).makerOrderId());
        assertEquals(50,
                book.orderById("b2").orElseThrow().quantity());

        assertTrue(book.orderById("s1").isEmpty());
    }

    @Test
    void partiallyMatchedGtcRestsResidualAndIocDoesNot() {
        book.add(order("b1", Side.BID, "50", 30, 1));

        book.add(new OrderRequest(
                "s1", Side.ASK, new BigDecimal("50"),
                50, t(2), "src", TimeInForce.GTC
        ));

        assertEquals(20,
                book.orderById("s1").orElseThrow().quantity());

        book.add(new OrderRequest(
                "b2", Side.BID, new BigDecimal("50"),
                100, t(3), "src", TimeInForce.IOC
        ));

        assertTrue(book.orderById("b2").isEmpty());
    }

    @Test
    void fokIsAllOrNothing() {
        book.add(order("a1", Side.ASK, "50", 10, 1));

        book.add(new OrderRequest(
                "b1", Side.BID, new BigDecimal("50"),
                11, t(2), "src", TimeInForce.FOK
        ));

        assertEquals(10,
                book.orderById("a1").orElseThrow().quantity());

        assertTrue(book.orderById("b1").isEmpty());

        assertEquals(
                OrderTransaction.Action.REJECT,
                book.lastTransaction("b1").orElseThrow().action()
        );
    }

    @Test
    void timeQueriesAndDuplicateIds() {
        book.add(order("1", Side.BID, "50", 10, 1));
        book.add(order("2", Side.ASK, "60", 10, 2));

        assertEquals(
                List.of("1"),
                book.createdBefore(t(2)).stream()
                        .map(OrderSnapshot::id).toList()
        );

        assertEquals(
                List.of("2"),
                book.createdAfter(t(1)).stream()
                        .map(OrderSnapshot::id).toList()
        );

        assertThrows(BookException.class,
                () -> book.add(order("1", Side.BID, "49", 1, 3)));
    }

    @Test
    void asyncAcknowledgesReceiptAndProcessesSerially() throws Exception {
        try (AsyncOrderBookService service =
                     new AsyncOrderBookService(book, f -> {})) {

            var submission =
                    service.submitAdd(order("1", Side.BID, "50", 10, 1));

            assertTrue(submission.receivedAck().get());
            assertTrue(submission.processingResult().get().isEmpty());

            assertEquals(
                    1,
                    service.query(() -> book.orderCount(Side.BID)).get()
            );
        }
    }

    // ==================================================
    // 2. NEGATIVE TESTS - ORDER CREATION
    // ==================================================

    @Test
    void shouldRejectDuplicateOrderId() {
        book.add(order("B1", Side.BID, "50", 100, 1));

        assertThrows(BookException.class,
                () -> book.add(order("B1", Side.BID, "55", 200, 2)));

        assertEquals(1, book.orderCount(Side.BID));
        assertEquals(100, book.orderById("B1").orElseThrow().quantity());
    }

    @Test
    void shouldRejectZeroQuantity() {
        assertThrows(IllegalArgumentException.class,
                () -> book.add(order("B1", Side.BID, "50", 0, 1)));
    }

    @Test
    void shouldRejectNegativeQuantity() {
        assertThrows(IllegalArgumentException.class,
                () -> book.add(order("B1", Side.BID, "50", -10, 1)));
    }

    @Test
    void shouldRejectNegativePrice() {
        assertThrows(IllegalArgumentException.class,
                () -> book.add(order("B1", Side.BID, "-50", 10, 1)));
    }

    @Test
    void shouldRejectZeroPrice() {
        assertThrows(IllegalArgumentException.class,
                () -> book.add(order("B1", Side.BID, "0", 10, 1)));
    }

    @Test
    void shouldRejectNullOrder() {
        assertThrows(Exception.class, () -> book.add(null));
    }

    // ==================================================
    // 3. NEGATIVE TESTS - AMENDMENT
    // ==================================================

    @Test
    void shouldRejectAmendmentForNonExistingOrder() {
        assertThrows(BookException.class,
                () -> book.amend(
                        "INVALID",
                        new BigDecimal("50"),
                        10,
                        t(2)
                ));
    }

    @Test
    void shouldRejectNegativeAmendmentQuantity() {
        book.add(order("B1", Side.BID, "50", 100, 1));

        assertThrows(BookException.class,
                () -> book.amend(
                        "B1", new BigDecimal("50"), -10, t(2)
                ));

        assertEquals(100,
                book.orderById("B1").orElseThrow().quantity());
    }

    @Test
    void shouldRejectZeroAmendmentQuantity() {
        book.add(order("B1", Side.BID, "50", 100, 1));

        assertThrows(BookException.class,
                () -> book.amend(
                        "B1", new BigDecimal("50"), 0, t(2)
                ));
    }

    @Test
    void shouldRejectNegativeAmendmentPrice() {
        book.add(order("B1", Side.BID, "50", 100, 1));

        assertThrows(BookException.class,
                () -> book.amend(
                        "B1", new BigDecimal("-50"), 100, t(2)
                ));

        assertEquals(new BigDecimal("50"),
                book.orderById("B1").orElseThrow().price());
    }

    @Test
    void shouldRejectAmendmentOfFullyFilledOrder() {
        book.add(order("S1", Side.ASK, "50", 10, 1));
        book.add(order("B1", Side.BID, "50", 10, 2));

        assertTrue(book.orderById("S1").isEmpty());

        assertThrows(BookException.class,
                () -> book.amend(
                        "S1", new BigDecimal("50"), 20, t(3)
                ));
    }

    // ==================================================
    // 4. NEGATIVE TESTS - CANCELLATION
    // ==================================================

    @Test
    void shouldRejectCancellationOfNonExistingOrder() {
        assertThrows(BookException.class,
                () -> book.remove("INVALID", t(1)));
    }

    @Test
    void shouldRejectRepeatedCancellation() {
        book.add(order("B1", Side.BID, "50", 100, 1));

        book.remove("B1", t(2));

        assertThrows(BookException.class,
                () -> book.remove("B1", t(3)));
    }

    @Test
    void shouldRejectCancellationOfFullyFilledOrder() {
        book.add(order("S1", Side.ASK, "50", 10, 1));
        book.add(order("B1", Side.BID, "50", 10, 2));

        assertThrows(BookException.class,
                () -> book.remove("S1", t(3)));
    }

    @Test
    void cancellationShouldRemoveOnlyRequestedOrder() {
        book.add(order("B1", Side.BID, "50", 100, 1));
        book.add(order("B2", Side.BID, "50", 200, 2));

        book.remove("B1", t(3));

        assertTrue(book.orderById("B1").isEmpty());
        assertTrue(book.orderById("B2").isPresent());

        assertEquals(1, book.orderCount(Side.BID));
    }

    @Test
    void cancellingLastOrderShouldRemovePriceLevel() {
        book.add(order("B1", Side.BID, "50", 100, 1));

        book.remove("B1", t(2));

        assertEquals(0, book.priceLevelCount(Side.BID));
        assertTrue(book.priceLevels(Side.BID).isEmpty());
    }

    // ==================================================
    // 5. NEGATIVE TESTS - MATCHING
    // ==================================================

    @Test
    void shouldNotMatchWhenBuyPriceIsLowerThanSellPrice() {
        book.add(order("S1", Side.ASK, "55", 100, 1));

        List<ExecutedTrade> trades =
                book.add(order("B1", Side.BID, "50", 100, 2));

        assertTrue(trades.isEmpty());

        assertTrue(book.orderById("S1").isPresent());
        assertTrue(book.orderById("B1").isPresent());
    }

    @Test
    void shouldNotMatchWhenSellPriceIsHigherThanBuyPrice() {
        book.add(order("B1", Side.BID, "50", 100, 1));

        List<ExecutedTrade> trades =
                book.add(order("S1", Side.ASK, "55", 100, 2));

        assertTrue(trades.isEmpty());

        assertTrue(book.orderById("B1").isPresent());
        assertTrue(book.orderById("S1").isPresent());
    }

    @Test
    void partiallyFilledOrderShouldRemainActive() {
        book.add(order("S1", Side.ASK, "50", 100, 1));

        book.add(order("B1", Side.BID, "50", 60, 2));

        assertTrue(book.orderById("B1").isEmpty());

        assertEquals(40,
                book.orderById("S1").orElseThrow().quantity());

        assertEquals(1, book.orderCount(Side.ASK));
    }

    @Test
    void fullyFilledOrdersShouldBeRemoved() {
        book.add(order("S1", Side.ASK, "50", 100, 1));

        book.add(order("B1", Side.BID, "50", 100, 2));

        assertTrue(book.orderById("S1").isEmpty());
        assertTrue(book.orderById("B1").isEmpty());

        assertEquals(0, book.orderCount(Side.ASK));
        assertEquals(0, book.orderCount(Side.BID));
    }

    // ==================================================
    // 6. NEGATIVE TESTS - TIME IN FORCE
    // ==================================================

    @Test
    void fokShouldNotPartiallyExecute() {
        book.add(order("S1", Side.ASK, "50", 5, 1));

        book.add(new OrderRequest(
                "B1", Side.BID, new BigDecimal("50"),
                10, t(2), "src", TimeInForce.FOK
        ));

        assertEquals(5,
                book.orderById("S1").orElseThrow().quantity());

        assertTrue(book.orderById("B1").isEmpty());

        assertEquals(
                OrderTransaction.Action.REJECT,
                book.lastTransaction("B1").orElseThrow().action()
        );
    }

    @Test
    void iocShouldCancelUnfilledQuantity() {
        book.add(order("S1", Side.ASK, "50", 5, 1));

        List<ExecutedTrade> trades = book.add(new OrderRequest(
                "B1", Side.BID, new BigDecimal("50"),
                10, t(2), "src", TimeInForce.IOC
        ));

        assertEquals(1, trades.size());

        assertTrue(book.orderById("B1").isEmpty());
        assertTrue(book.orderById("S1").isEmpty());
    }

    @Test
    void iocShouldCancelEntireOrderWhenNoLiquidityExists() {
        List<ExecutedTrade> trades = book.add(new OrderRequest(
                "B1", Side.BID, new BigDecimal("50"),
                10, t(1), "src", TimeInForce.IOC
        ));

        assertTrue(trades.isEmpty());
        assertTrue(book.orderById("B1").isEmpty());
    }
}
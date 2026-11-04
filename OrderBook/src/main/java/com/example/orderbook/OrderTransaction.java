package com.example.orderbook;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Audit description of the most recent accepted transaction for an order id.
 */
public record OrderTransaction(String orderId, Action action, Instant transactionTime,
                               BigDecimal price, Long quantity, String note) {
    public enum Action {ADD, AMEND, REMOVE, FILL, REJECT}
}

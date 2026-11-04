package com.example.orderbook;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable command to add an order. Prices use BigDecimal to avoid binary floating-point errors.
 */
public record OrderRequest(String id, Side side, BigDecimal price, long quantity,
                           Instant transactionTime, String sourceId, TimeInForce timeInForce) {
    public OrderRequest {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id is required");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(transactionTime, "transactionTime");
        Objects.requireNonNull(timeInForce, "timeInForce");
        if (price.signum() <= 0) throw new IllegalArgumentException("price must be positive");
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
        sourceId = sourceId == null ? "unknown" : sourceId;
    }

    public OrderRequest(String id, Side side, BigDecimal price, long quantity, Instant transactionTime) {
        this(id, side, price, quantity, transactionTime, "unknown", TimeInForce.GTC);
    }
}

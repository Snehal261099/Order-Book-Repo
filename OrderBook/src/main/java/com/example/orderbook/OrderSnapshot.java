package com.example.orderbook;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable view; callers never receive the mutable internal order.
 */
public record  OrderSnapshot(String id, Side side, BigDecimal price, long quantity,
                             Instant creationTime, Instant lastUpdateTime, Instant priorityTime,
                             long prioritySequence, String sourceId) {
}

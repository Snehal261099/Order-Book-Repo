package com.example.orderbook;

import java.math.BigDecimal;
import java.time.Instant;

/** One execution between an incoming/taker order and a resting/maker order. */
public record ExecutedTrade(String takerOrderId, String makerOrderId, Side takerSide,
                           BigDecimal price, long quantity, Instant time,
                           String takerSourceId, String makerSourceId) {}

package com.example.orderbook;

import java.math.BigDecimal;
import java.time.Instant;

public record ExecutedTrade(String takerOrderId, String makerOrderId, Side takerSide,
                           BigDecimal price, long quantity, Instant time,
                           String takerSourceId, String makerSourceId) {}

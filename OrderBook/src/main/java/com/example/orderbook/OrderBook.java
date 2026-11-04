package com.example.orderbook;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderBook {
    List<ExecutedTrade> add(OrderRequest request);

    void amend(String id, BigDecimal newPrice, long newQuantity, Instant transactionTime);

    void remove(String id, Instant transactionTime);

    boolean isCrossed();

    Optional<BigDecimal> topPrice(Side side);

    Optional<BigDecimal> bottomPrice(Side side);

    int priceLevelCount(Side side);

    List<BigDecimal> priceLevels(Side side);

    int orderCountAtPrice(Side side, BigDecimal price);

    List<OrderSnapshot> ordersAtPrice(Side side, BigDecimal price);

    long orderCount(Side side);

    List<OrderSnapshot> ordersByPriority(Side side);

    Optional<OrderSnapshot> orderById(String id);

    Optional<OrderTransaction> lastTransaction(String id);

    List<OrderSnapshot> createdBefore(Instant time);

    List<OrderSnapshot> createdAfter(Instant time);

    List<OrderSnapshot> updatedBefore(Instant time);

    List<OrderSnapshot> updatedAfter(Instant time);
}

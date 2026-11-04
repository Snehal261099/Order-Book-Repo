package com.example.orderbook;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public class InMemoryOrderBook implements OrderBook {
        private static final class MutableOrder {
            String id;
            Side side;
            BigDecimal price;
            long quantity;
            Instant creationTime, lastUpdateTime, priorityTime;
            long prioritySequence;
            String sourceId;

            MutableOrder(OrderRequest r, long seq) {
                id = r.id();
                side = r.side();
                price = r.price();
                quantity = r.quantity();
                creationTime = r.transactionTime();
                lastUpdateTime = r.transactionTime();
                priorityTime = r.transactionTime();
                prioritySequence = seq;
                sourceId = r.sourceId();
            }

            OrderSnapshot snapshot() {
                return new OrderSnapshot(id, side, price, quantity, creationTime, lastUpdateTime, priorityTime, prioritySequence, sourceId);
            }
        }

        private static final class OrdersAtPriceLevel {
            final TreeSet<MutableOrder> ordersByTimePriority = new TreeSet<>(Comparator
                    .comparing((MutableOrder o) -> o.priorityTime).thenComparingLong(o -> o.prioritySequence));
        }

        private final NavigableMap<BigDecimal, OrdersAtPriceLevel> buyOrdersByPrice = new TreeMap<>(Comparator.reverseOrder());
        private final NavigableMap<BigDecimal, OrdersAtPriceLevel> sellOrdersByPrice = new TreeMap<>();
        private final Map<String, MutableOrder> activeOrdersById = new HashMap<>();
        private final Map<String, OrderTransaction> lastTransactions = new HashMap<>();
        private long sequence;

        private NavigableMap<BigDecimal, OrdersAtPriceLevel> levels(Side side) {
            return side == Side.BID ? buyOrdersByPrice : sellOrdersByPrice;
        }

        @Override
        public List<ExecutedTrade> add(OrderRequest r) {
            Objects.requireNonNull(r, "request");
            if (activeOrdersById.containsKey(r.id()) || lastTransactions.containsKey(r.id()))
                throw new BookException("Order id has already been used: " + r.id());
            MutableOrder incoming = new MutableOrder(r, ++sequence);
            List<ExecutedTrade> executedTrades = new ArrayList<>();
            if (r.timeInForce() == TimeInForce.FOK && executableQuantity(incoming) < incoming.quantity) {
                lastTransactions.put(r.id(), new OrderTransaction(r.id(), OrderTransaction.Action.REJECT,
                        r.transactionTime(), r.price(), r.quantity(), "FOK not fully executable"));
                return List.of();
            }
            match(incoming, executedTrades, r.transactionTime());
            if (incoming.quantity > 0 && r.timeInForce() == TimeInForce.GTC) {
                insertIntoActiveOrders(incoming);
                lastTransactions.put(r.id(), new OrderTransaction(r.id(), OrderTransaction.Action.ADD,
                        r.transactionTime(), incoming.price, incoming.quantity, "resting"));
            } else if (incoming.quantity == 0) {
                lastTransactions.put(r.id(), new OrderTransaction(r.id(), OrderTransaction.Action.FILL,
                        r.transactionTime(), r.price(), 0L, "fully filled"));
            } else {
                lastTransactions.put(r.id(), new OrderTransaction(r.id(), OrderTransaction.Action.REMOVE,
                        r.transactionTime(), incoming.price, incoming.quantity, "IOC residual cancelled"));
            }
            return List.copyOf(executedTrades);
        }

        private long executableQuantity(MutableOrder incoming) {
            long total = 0;
            NavigableMap<BigDecimal, OrdersAtPriceLevel> opposite = levels(incoming.side == Side.BID ? Side.ASK : Side.BID);
            for (var e : opposite.entrySet()) {
                if (!crosses(incoming.side, incoming.price, e.getKey())) break;
                for (MutableOrder o : e.getValue().ordersByTimePriority) {
                    if (Long.MAX_VALUE - total < o.quantity) return Long.MAX_VALUE;
                    total += o.quantity;
                    if (total >= incoming.quantity) return total;
                }
            }
            return total;
        }

        private void match(MutableOrder taker, List<ExecutedTrade> executedTrades, Instant time) {
            NavigableMap<BigDecimal, OrdersAtPriceLevel> opposite = levels(taker.side == Side.BID ? Side.ASK : Side.BID);
            while (taker.quantity > 0 && !opposite.isEmpty()) {
                var first = opposite.firstEntry();
                if (!crosses(taker.side, taker.price, first.getKey())) break;
                MutableOrder maker = first.getValue().ordersByTimePriority.first();
                long traded = Math.min(taker.quantity, maker.quantity);
                executedTrades.add(new ExecutedTrade(taker.id, maker.id, taker.side, maker.price, traded, time, taker.sourceId, maker.sourceId));
                taker.quantity -= traded;
                maker.quantity -= traded;
                maker.lastUpdateTime = time;
                lastTransactions.put(maker.id, new OrderTransaction(maker.id, OrderTransaction.Action.FILL,
                        time, maker.price, maker.quantity, "matched with " + taker.id));
                if (maker.quantity == 0) removeFromBook(maker);
                else {
                    // Quantity reduction due to execution retains maker priority.
                    // TreeSet comparator does not depend on quantity, so no reindex is needed.
                }
            }
        }

        private boolean crosses(Side takerSide, BigDecimal takerPrice, BigDecimal oppositePrice) {
            return takerSide == Side.BID ? takerPrice.compareTo(oppositePrice) >= 0
                    : takerPrice.compareTo(oppositePrice) <= 0;
        }

        private void insertIntoActiveOrders(MutableOrder o) {
            levels(o.side).computeIfAbsent(o.price, p -> new OrdersAtPriceLevel()).ordersByTimePriority.add(o);
            activeOrdersById.put(o.id, o);
        }

        private void removeFromBook(MutableOrder o) {
            NavigableMap<BigDecimal, OrdersAtPriceLevel> map = levels(o.side);
            OrdersAtPriceLevel level = map.get(o.price);
            if (level != null) {
                level.ordersByTimePriority.remove(o);
                if (level.ordersByTimePriority.isEmpty()) map.remove(o.price);
            }
            activeOrdersById.remove(o.id);
        }

        @Override
        public void amend(String id, BigDecimal newPrice, long newQuantity, Instant time) {
            Objects.requireNonNull(newPrice);
            Objects.requireNonNull(time);
            if (newPrice.signum() <= 0 || newQuantity <= 0) throw new BookException("Price and quantity must be positive");
            MutableOrder o = activeOrdersById.get(id);
            if (o == null) throw new BookException("Unknown active order: " + id);
            BigDecimal normalized = newPrice;
            boolean samePrice = o.price.compareTo(normalized) == 0;
            boolean reduceOnly = samePrice && newQuantity <= o.quantity;
            if (!reduceOnly) {
                removeFromBook(o);
                o.price = normalized;
                o.quantity = newQuantity;
                o.priorityTime = time;
                o.prioritySequence = ++sequence;
                insertIntoActiveOrders(o);
            } else {
                o.quantity = newQuantity;
            }
            o.lastUpdateTime = time;
            lastTransactions.put(id, new OrderTransaction(id, OrderTransaction.Action.AMEND, time, o.price, o.quantity,
                    reduceOnly ? "quantity reduced; priority retained" : "priority reset"));
        }

        @Override
        public void remove(String id, Instant time) {
            Objects.requireNonNull(time);
            MutableOrder o = activeOrdersById.get(id);
            if (o == null) throw new BookException("Unknown active order: " + id);
            removeFromBook(o);
            lastTransactions.put(id, new OrderTransaction(id, OrderTransaction.Action.REMOVE, time, o.price, o.quantity, "cancelled"));
        }

        @Override
        public boolean isCrossed() {
            return !buyOrdersByPrice.isEmpty() && !sellOrdersByPrice.isEmpty() && buyOrdersByPrice.firstKey().compareTo(sellOrdersByPrice.firstKey()) >= 0;
        }

        @Override
        public Optional<BigDecimal> topPrice(Side s) {
            return Optional.ofNullable(levels(s).isEmpty() ? null : levels(s).firstKey());
        }

        @Override
        public Optional<BigDecimal> bottomPrice(Side s) {
            return Optional.ofNullable(levels(s).isEmpty() ? null : levels(s).lastKey());
        }

        @Override
        public int priceLevelCount(Side s) {
            return levels(s).size();
        }

        @Override
        public List<BigDecimal> priceLevels(Side s) {
            return List.copyOf(levels(s).keySet());
        }

        @Override
        public int orderCountAtPrice(Side s, BigDecimal p) {
            OrdersAtPriceLevel l = levels(s).get(p);
            return l == null ? 0 : l.ordersByTimePriority.size();
        }

        @Override
        public List<OrderSnapshot> ordersAtPrice(Side s, BigDecimal p) {
            OrdersAtPriceLevel l = levels(s).get(p);
            if (l == null) return List.of();
            return l.ordersByTimePriority.stream().map(MutableOrder::snapshot).toList();
        }

        @Override
        public long orderCount(Side s) {
            return levels(s).values().stream().mapToLong(l -> l.ordersByTimePriority.size()).sum();
        }

        @Override
        public List<OrderSnapshot> ordersByPriority(Side s) {
            return levels(s).values().stream().flatMap(l -> l.ordersByTimePriority.stream()).map(MutableOrder::snapshot).toList();
        }

        @Override
        public Optional<OrderSnapshot> orderById(String id) {
            return Optional.ofNullable(activeOrdersById.get(id)).map(MutableOrder::snapshot);
        }

        @Override
        public Optional<OrderTransaction> lastTransaction(String id) {
            return Optional.ofNullable(lastTransactions.get(id));
        }

        @Override
        public List<OrderSnapshot> createdBefore(Instant t) {
            return timeQuery(t, true, true);
        }

        @Override
        public List<OrderSnapshot> createdAfter(Instant t) {
            return timeQuery(t, true, false);
        }

        @Override
        public List<OrderSnapshot> updatedBefore(Instant t) {
            return timeQuery(t, false, true);
        }

        @Override
        public List<OrderSnapshot> updatedAfter(Instant t) {
            return timeQuery(t, false, false);
        }

        private List<OrderSnapshot> timeQuery(Instant t, boolean creation, boolean before) {
            Objects.requireNonNull(t);
            return activeOrdersById.values().stream()
                    .filter(o -> before ? (creation ? o.creationTime : o.lastUpdateTime).isBefore(t)
                            : (creation ? o.creationTime : o.lastUpdateTime).isAfter(t))
                    .map(MutableOrder::snapshot)
                    .sorted(Comparator.comparing(creation ? OrderSnapshot::creationTime : OrderSnapshot::lastUpdateTime)
                            .thenComparingLong(OrderSnapshot::prioritySequence)).toList();
        }

    }

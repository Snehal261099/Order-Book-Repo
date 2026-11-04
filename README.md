# Order-Book-Repo
Order book implementation
# In-memory Order Book (Java 21)

A documented, tested Maven project implementing a price/time-priority limit order book, matching, amendments, cancellation, queries and a serialized asynchronous facade.

## Requirements and design choices

- **Bid price levels:** descending price. **Ask price levels:** ascending price.

- At one price, priority is earliest `priorityTime`, then a monotonic sequence as deterministic tie-breaker.

- Each order carries id, side, price, remaining quantity, creation time, last-update time, priority time, sequence and source id.

- Add IDs are unique for the lifetime of this book, including IDs that were cancelled, filled, or rejected.

- Amendment policy: same-price quantity reduction preserves queue priority; price change or quantity increase loses priority. `lastUpdateTime` still changes on a reduction.

- Crossed means best bid >= best ask.

- Matching uses the resting maker's price; it consumes the best opposite price then time priority.

- GTC rests residual quantity; IOC cancels residual; FOK first checks executable liquidity and executes all or none.

- `lastTransaction` retains the latest transaction even after an order leaves the active book.

- Time-query methods return active orders only. Historical queries across removed/filled orders would need a separate event store.

## Build and test

Requires JDK 21 and Maven 3.9+.

```bash

mvn clean test

mvn package

```

No external runtime dependencies. JUnit 5 is used for tests.

## Main classes

- `OrderBook`: complete public query/mutation interface with JavaDoc.

- `InMemoryOrderBook`: single-writer core; intentionally not thread-safe.

- `AsyncOrderBookService`: accepts multi-threaded submissions, serializes book mutations on one worker, acknowledges queue acceptance separately from processing completion, and dispatches fills on a separate executor.

- `OrderRequest`, `OrderSnapshot`, `Fill`, `OrderTransaction`: immutable API records.

## Complexity

Let `L` be price levels, `N` active orders, `Np` orders at a price, and `K` fills for a transaction.

| Operation | Expected complexity |

|---|---:|

| Add resting order | O(log L + log Np) plus ID map expected O(1) |

| Cancel | O(log L + log Np), plus expected O(1) ID lookup |

| Amend retaining priority | O(1) field update |

| Amend losing priority | O(log L + log Np) |

| Best price / crossed check | O(1) |

| Price level count / level iteration | O(1) / O(L) |

| Iterate orders at price | O(Np) |

| Iterate all orders on side | O(N) |

| Match | O((K + touched levels) log Np) approximately |

| Time filters in this version | O(N log N) because results are sorted |

The core uses `TreeMap<BigDecimal, PriceLevel>` for price ordering, `TreeSet` for time priority, and `HashMap<String, MutableOrder>` for ID lookup. `BigDecimal` is chosen for exact decimal prices; a fixed-point `long` tick representation is usually faster if tick size and scale are fixed.

## Concurrency and production notes

The core is single-writer. This is a deliberate simplification: a matching engine must apply transactions in one deterministic order. `AsyncOrderBookService` serializes writes and schedules fill publication separately. Its acknowledgement means **accepted into the in-memory executor queue**, not durably persisted and not yet matched. The current executor uses an unbounded queue, suitable for an educational sample but not production.

For production:

1. Use a bounded queue and explicit overload/rejection/back-pressure behavior.

2. Persist inbound transactions to a durable log before acknowledging receipt (e.g., Kafka with idempotency key).

3. Partition books by instrument/symbol so each book has one ordered writer; never mutate one book concurrently.

4. Publish fills through a transactional outbox or durable event log; retry with idempotent fill IDs.

5. Define ordering for equal transaction timestamps; this implementation uses processing order/sequence as tie-breaker.

6. Decide what happens when a transaction timestamp is older than the current book state; this sample accepts it and the priority comparator orders by timestamp.

7. Add metrics: queue depth, rejection rate, matching latency, fill count, active orders, GC and allocation rate.

8. Consider persistence/recovery, snapshots, replay, market data, self-trade prevention, price bands, tick/lot validation, partial failure and audit retention.

## Patterns used

- **Strategy/data-structure strategy:** side-specific `NavigableMap` comparators express bid/ask ordering.

- **Facade / Service layer:** `AsyncOrderBookService` is a simplified asynchronous entry point around the synchronous domain core.

- **Command-like API:** `OrderRequest` and method arguments represent transactions.

- **Immutable DTOs:** records expose snapshots rather than mutable internal objects.

- **Observer/callback:** fill publisher receives fill events asynchronously.

- **Single-writer actor model:** one executor owns all mutations, avoiding fine-grained locking and race conditions.

Avoid claiming the project uses every named GoF pattern. The primary design choice is a single-writer core with explicit data structures.

## Known limitations and alternatives

- Time queries scan active orders. For high query volume, add `NavigableSet` indexes by creation/update time and remove/reinsert on updates.

- FOK executable-quantity precheck is safe under the single-writer assumption. It is O(number of executable resting orders) before the actual match; alternatively simulate levels or maintain aggregate level quantities.

- Fill publishing is asynchronous but not durable. A process crash can lose queued fills.

- No source authentication, persistence, network protocol, metrics, or replay are included.

- Time priority is based on supplied transaction time. A production feed may need exchange sequence numbers and validation against out-of-order timestamps.

- If “last transaction” must include every event, replace the single last-event map with an append-only event history.

- If amended orders should immediately match after price/quantity increase, this sample's `amend` only repositions the order; matching-on-amend is a documented extension. Implement by removing, applying amendment, treating it as a taker, matching, then resting any residual.

- No `synchronized` blocks are used in the core because serialization is the ownership model. Do not call core mutators from arbitrary threads.
 

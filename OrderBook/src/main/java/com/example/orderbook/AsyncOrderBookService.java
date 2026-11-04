package com.example.orderbook;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Serializes all book mutations on one worker, while accepting calls from many producer threads.
 * Receipt acknowledgement is completed as soon as a command is accepted into the queue;
 * processingResult completes when the command has actually been applied.
 * <p>
 * Fill listeners run on a separate executor so slow downstream publishing does not block matching.
 * Production deployments should use bounded queues, back-pressure, durable ingress and retry/outbox.
 */
public final class AsyncOrderBookService implements AutoCloseable {
    public record Submission(CompletableFuture<Boolean> receivedAck,
                             CompletableFuture<List<ExecutedTrade>> processingResult) {
    }

    private final OrderBook book;
    private final ExecutorService writerExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "order-book-writer"));
    private final ExecutorService publisherExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "executedTrade-publisher"));
    private final Consumer<ExecutedTrade> tradePublisher;

    public AsyncOrderBookService(OrderBook book, Consumer<ExecutedTrade> tradePublisher) {
        this.book = Objects.requireNonNull(book);
        this.tradePublisher = Objects.requireNonNull(tradePublisher);
    }

    public Submission submitAdd(OrderRequest request) {
        CompletableFuture<Boolean> ack = new CompletableFuture<>();
        CompletableFuture<List<ExecutedTrade>> result = new CompletableFuture<>();
        try {
            writerExecutor.execute(() -> {
                try {
                    List<ExecutedTrade> executedTrades = book.add(request);
                    executedTrades.forEach(f -> publisherExecutor.execute(() -> tradePublisher.accept(f)));
                    result.complete(executedTrades);
                } catch (Throwable t) {
                    result.completeExceptionally(t);
                }
            });
            ack.complete(true);
        } catch (RejectedExecutionException ex) {
            ack.completeExceptionally(ex);
            result.completeExceptionally(ex);
        }
        return new Submission(ack, result);
    }

    public CompletableFuture<Void> amend(String id, BigDecimal price, long quantity, Instant time) {
        return CompletableFuture.runAsync(() -> book.amend(id, price, quantity, time), writerExecutor);
    }

    public CompletableFuture<Void> remove(String id, Instant time) {
        return CompletableFuture.runAsync(() -> book.remove(id, time), writerExecutor);
    }

    /**
     * Read operations should be scheduled on the same executor if a consistent concurrent snapshot is required.
     */
    public <T> CompletableFuture<T> query(Callable<T> query) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return query.call();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, writerExecutor);
    }

    @Override
    public void close() {
        writerExecutor.shutdown();
        publisherExecutor.shutdown();
        try {
            if (!writerExecutor.awaitTermination(5, TimeUnit.SECONDS)) writerExecutor.shutdownNow();
            if (!publisherExecutor.awaitTermination(5, TimeUnit.SECONDS)) publisherExecutor.shutdownNow();
        } catch (InterruptedException e) {
            writerExecutor.shutdownNow();
            publisherExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
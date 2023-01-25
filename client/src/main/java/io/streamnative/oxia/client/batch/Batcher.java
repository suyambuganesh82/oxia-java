package io.streamnative.oxia.client.batch;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static lombok.AccessLevel.PACKAGE;

import io.streamnative.oxia.client.ClientConfig;
import io.streamnative.oxia.proto.OxiaClientGrpc.OxiaClientBlockingStub;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;

@RequiredArgsConstructor(access = PACKAGE)
public class Batcher implements Runnable, AutoCloseable {

    @NonNull private final ClientConfig config;
    private final long shardId;
    @NonNull private final Function<Long, Batch> batchFactory;
    @NonNull private final BlockingQueue<Operation<?>> operations;
    @NonNull private final Clock clock;
    private volatile boolean closed;

    Batcher(@NonNull ClientConfig config, long shardId, @NonNull Function<Long, Batch> batchFactory) {
        this(
                config,
                shardId,
                batchFactory,
                new ArrayBlockingQueue<>(config.operationQueueCapacity()),
                Clock.systemUTC());
    }

    @SneakyThrows
    public <R> void add(@NonNull Operation<R> operation) {
        var timeout = config.requestTimeout();
        try {
            if (!operations.offer(operation, timeout.toMillis(), MILLISECONDS)) {
                throw new TimeoutException(
                        "Queue full - could not add new operation. Consider increasing 'operationQueueCapacity'");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    @Override
    public void run() {
        Batch batch = null;
        var lingerBudgetMs = -1L;
        while (!closed) {
            try {
                Operation<?> operation = null;
                if (batch == null) {
                    operation = operations.poll();
                } else {
                    operation = operations.poll(lingerBudgetMs, MILLISECONDS);
                    var spentLingerBudgetMs = Math.max(0, clock.millis() - batch.getStartTime());
                    lingerBudgetMs = Math.max(0L, lingerBudgetMs - spentLingerBudgetMs);
                }

                if (operation != null) {
                    if (batch == null) {
                        batch = batchFactory.apply(shardId);
                        lingerBudgetMs = config.batchLinger().toMillis();
                    }
                    batch.add(operation);
                }

                if (batch != null) {
                    if (batch.size() == config.maxRequestsPerBatch() || lingerBudgetMs == 0) {
                        batch.complete();
                        batch = null;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void close() throws Exception {
        closed = true;
    }

    static @NonNull Function<Long, Batcher> newReadBatcherFactory(
            @NonNull ClientConfig config,
            @NonNull Function<Long, Optional<OxiaClientBlockingStub>> clientByShardId,
            Clock clock) {
        return s -> new Batcher(config, s, new Batch.ReadBatchFactory(clientByShardId, config, clock));
    }

    static @NonNull Function<Long, Batcher> writeReadBatcherFactory(
            @NonNull ClientConfig config,
            @NonNull Function<Long, Optional<OxiaClientBlockingStub>> clientByShardId,
            Clock clock) {
        return s -> new Batcher(config, s, new Batch.ReadBatchFactory(clientByShardId, config, clock));
    }
}
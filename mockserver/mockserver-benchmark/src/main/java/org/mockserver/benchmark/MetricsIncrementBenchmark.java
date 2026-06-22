package org.mockserver.benchmark;

import org.mockserver.configuration.Configuration;
import org.mockserver.metrics.Metrics;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.ThreadParams;

import java.util.concurrent.TimeUnit;

/**
 * Contention micro-benchmark for {@link Metrics#increment(Metrics.Name)}.
 *
 * <p>Models the production hot path: every worker thread, on the Netty event loop when
 * {@code metricsEnabled} is on, increments the <em>same</em> shared counter once per request.
 * On the baseline implementation every {@code increment()} call entered {@code synchronized (name)}
 * on the shared {@code Name} enum constant, serializing all worker threads on that one monitor.
 * The fix adds a double-checked lookup ({@code ConcurrentHashMap.get} before the lock) so the
 * already-registered hot path is lock-free.
 *
 * <p>Run with high thread counts to expose the contention:
 *
 * <pre>./run.sh -t 32 MetricsIncrementBenchmark
 * ./run.sh MetricsIncrementBenchmark            # uses the @Threads(32) default below</pre>
 *
 * <p>{@link Mode#Throughput} (ops/us, higher is better) is the headline metric for a contended
 * lock; average-time is also reported. Compare the number before vs after the getOrCreate
 * double-checked-lookup change — a clear increase under contention is the proof the lock removal
 * helps; if it does not move, the lock was not the bottleneck and the change should be dropped.
 */
@State(Scope.Benchmark)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@Threads(32)
public class MetricsIncrementBenchmark {

    private Metrics metrics;
    private Metrics.Name[] names;

    @Setup(Level.Trial)
    public void setup() {
        // enable metrics so increment() actually does the getOrCreate lookup (the contended path)
        Configuration configuration = Configuration.configuration().metricsEnabled(true);
        metrics = new Metrics(configuration);
        names = Metrics.Name.values();
        // pre-register every Name so each measured increment() hits the already-registered fast path
        // (the realistic steady state — registration is one-time at startup)
        for (Metrics.Name name : names) {
            metrics.increment(name);
        }
    }

    /**
     * Worst case for the lock: all threads increment the SAME Name, so on baseline they serialize
     * on that single shared monitor. NOTE the same Name also means all threads CAS the same shared
     * AtomicLong inside Gauge.inc(), which is itself a contention point independent of the lock.
     */
    @Benchmark
    public void incrementSharedCounter() {
        metrics.increment(Metrics.Name.REQUESTS_RECEIVED_COUNT);
    }

    /**
     * Realistic mixed case: each thread increments a DIFFERENT Name. On baseline {@code
     * synchronized(name)} locks the per-Name monitor, so distinct Names never contend on the lock;
     * each thread also CASes its own distinct gauge AtomicLong. This isolates the lock-acquire cost
     * itself (uncontended monitor) from the same-counter CAS contention above.
     */
    @Benchmark
    public void incrementDistinctCounterPerThread(ThreadParams threads) {
        metrics.increment(names[threads.getThreadIndex() % names.length]);
    }
}

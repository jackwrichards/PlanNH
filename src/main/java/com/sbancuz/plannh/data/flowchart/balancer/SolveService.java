package com.sbancuz.plannh.data.flowchart.balancer;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.sbancuz.plannh.PlanNH;

/**
 * Solves plans on one background thread so the board never waits for the solver.
 *
 * <p>
 * The client thread takes a {@link SolveInput} snapshot whenever the graph's version moves and
 * {@link #submit}s it; the worker solves it with {@link Balancer#solve(SolveInput)} and publishes
 * the answer, which the board reads every frame with {@link #latest()}. Only the newest request
 * matters:
 * <ul>
 * <li>a request that has not started yet is replaced by the next {@code submit}, so a burst of edits
 * costs one solve, not one per edit;</li>
 * <li>a request that was already solving when a newer one arrived finishes but is dropped as stale -
 * its answer belongs to a graph that no longer exists - and the worker moves on to the newer one.
 * Stale is decided by submission order, never by comparing version numbers, so switching back to a
 * plan tab whose version is lower than the last one submitted still publishes.</li>
 * </ul>
 * {@code latest()} therefore lags the graph while a solve runs: compare {@link Result#version()}
 * with the graph's version to know whether what is on screen is current, and {@link #busy()} to
 * say "solving".
 *
 * <p>
 * A solve that throws is published as a {@link Result} with its {@link Result#error()} set and no
 * balance, and the worker carries on with the next request. The worker is a daemon thread, so it
 * never keeps the game from exiting; {@link #close()} stops it.
 *
 * <p>
 * Thread contract: {@code submit}, {@code request}, {@code latest}, {@code busy} and {@code close}
 * may be called from any thread; building the snapshot ({@link SolveInput#of}) is client-thread
 * work and happens in the caller (inside {@link #request}, on the calling thread).
 */
public final class SolveService implements AutoCloseable {

    /**
     * One finished solve.
     *
     * @param version    the version the caller submitted with the snapshot.
     * @param input      the snapshot that was solved.
     * @param balance    the answer, or null when the solve threw.
     * @param error      what the solve threw, or null when it finished.
     * @param wallMillis how long the solve took.
     */
    public record Result(long version, SolveInput input, @Nullable BalanceResult balance, @Nullable Throwable error,
        long wallMillis) {

        /** Whether the solve finished (even with a failed balance) rather than throwing. */
        public boolean ok() {
            return balance != null;
        }

        /** The error as a solver note, for the board's notices; null when the solve finished. */
        @Nullable
        public Note errorNote() {
            if (error == null) return null;
            final String message = error.getMessage();
            return SolverMessage.SOLVE_CRASHED.toNote(
                message == null ? error.getClass()
                    .getSimpleName() : message);
        }
    }

    private record Request(long seq, long version, SolveInput input) {}

    private final Function<SolveInput, BalanceResult> solver;
    private final Thread worker;
    private final Object lock = new Object();

    // Guarded by lock.
    private @Nullable Request pending;
    private boolean running;
    private long lastSeq;
    private long lastVersion = Long.MIN_VALUE;
    private boolean closed;

    private volatile @Nullable Result latest;

    /** A service solving with {@link Balancer#solve(SolveInput)}. */
    public SolveService() {
        this(Balancer::solve);
    }

    /** A service solving with {@code solver}; tests hand in a solver they control. */
    public SolveService(final Function<SolveInput, BalanceResult> solver) {
        this.solver = Objects.requireNonNull(solver, "solver");
        this.worker = new Thread(this::loop, "PlanNH solver");
        worker.setDaemon(true);
        worker.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        worker.start();
    }

    /**
     * Queues a snapshot to solve, replacing any request that has not started. {@code version} is
     * echoed on the {@link Result}; use the graph's {@code version()}.
     */
    public void submit(final long version, final SolveInput input) {
        Objects.requireNonNull(input, "input");
        synchronized (lock) {
            if (closed) return;
            pending = new Request(++lastSeq, version, input);
            lastVersion = version;
            lock.notifyAll();
        }
    }

    /**
     * Submits {@code snapshot.get()} unless {@code version} is the version last submitted, so the
     * board can call this every frame and only pay for a snapshot when the graph changed. The
     * snapshot is taken on the calling thread. Returns whether a request was submitted.
     */
    public boolean request(final long version, final Supplier<SolveInput> snapshot) {
        synchronized (lock) {
            if (closed || version == lastVersion && lastSeq > 0) return false;
        }
        submit(version, snapshot.get());
        return true;
    }

    /** The newest published result, or null before the first one. */
    @Nullable
    public Result latest() {
        return latest;
    }

    /** Whether a request is waiting or solving. */
    public boolean busy() {
        synchronized (lock) {
            return pending != null || running;
        }
    }

    /**
     * Waits until nothing is waiting or solving, or the timeout passes; true when idle. For tests
     * and shutdown, never the render thread.
     */
    public boolean awaitIdle(final long timeoutMillis) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        synchronized (lock) {
            while (pending != null || running) {
                final long left = deadline - System.currentTimeMillis();
                if (left <= 0) return false;
                lock.wait(left);
            }
            return true;
        }
    }

    /** Stops the worker; a solve in progress finishes but is not published. Idempotent. */
    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            pending = null;
            lock.notifyAll();
        }
    }

    private void loop() {
        while (true) {
            final Request request;
            synchronized (lock) {
                while (pending == null && !closed) {
                    try {
                        lock.wait();
                    } catch (final InterruptedException e) {
                        Thread.currentThread()
                            .interrupt();
                        closed = true;
                    }
                }
                if (closed) {
                    running = false;
                    lock.notifyAll();
                    return;
                }
                request = pending;
                pending = null;
                running = true;
            }
            final long start = System.currentTimeMillis();
            Result result;
            try {
                final BalanceResult balance = solver.apply(request.input());
                result = new Result(
                    request.version(),
                    request.input(),
                    balance,
                    null,
                    System.currentTimeMillis() - start);
            } catch (final Throwable t) {
                PlanNH.LOG.error("Solve of version {} threw", request.version(), t);
                result = new Result(request.version(), request.input(), null, t, System.currentTimeMillis() - start);
            }
            synchronized (lock) {
                running = false;
                // Stale: a newer request arrived while this one was solving.
                if (!closed && request.seq() == lastSeq) latest = result;
                lock.notifyAll();
            }
        }
    }
}

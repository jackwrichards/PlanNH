package com.gtnhplanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.balancer.BalanceMode;
import com.gtnhplanner.data.flowchart.balancer.BalanceResult;
import com.gtnhplanner.data.flowchart.balancer.Balancer;
import com.gtnhplanner.data.flowchart.balancer.SolveInput;
import com.gtnhplanner.data.flowchart.balancer.SolveService;
import com.gtnhplanner.data.flowchart.balancer.SolverMessage;
import com.gtnhplanner.harness.GtnhFlowLoader;
import com.gtnhplanner.harness.GtnhFlowLoader.LoadedChart;

/** The background solver: newest request wins, stale answers are dropped, a crash is survived. */
class SolveServiceTest {

    private static final long WAIT = 30_000;

    @Test
    void aBurstOfEditsSolvesTheFirstAndTheLastAndPublishesOnlyTheLast() throws Exception {
        final SolveInput a = empty();
        final SolveInput b = empty();
        final SolveInput c = empty();
        final CountDownLatch aStarted = new CountDownLatch(1);
        final CountDownLatch releaseA = new CountDownLatch(1);
        final CountDownLatch cStarted = new CountDownLatch(1);
        final CountDownLatch releaseC = new CountDownLatch(1);
        final List<SolveInput> solved = Collections.synchronizedList(new ArrayList<>());
        try (SolveService service = new SolveService(input -> {
            solved.add(input);
            if (input == a) {
                aStarted.countDown();
                await(releaseA);
            } else if (input == c) {
                cStarted.countDown();
                await(releaseC);
            }
            return Balancer.solve(input);
        })) {
            service.submit(1, a);
            assertTrue(aStarted.await(WAIT, TimeUnit.MILLISECONDS));
            assertTrue(service.busy(), "solving");

            service.submit(2, b); // replaced before it starts
            service.submit(3, c);
            releaseA.countDown();

            assertTrue(cStarted.await(WAIT, TimeUnit.MILLISECONDS));
            assertNull(service.latest(), "version 1 finished after newer edits: stale, so never shown");

            releaseC.countDown();
            assertTrue(service.awaitIdle(WAIT));
            assertFalse(service.busy());
            assertEquals(List.of(a, c), solved, "version 2 was never solved");
            final SolveService.Result latest = service.latest();
            assertNotNull(latest);
            assertEquals(3, latest.version());
            assertSame(c, latest.input());
            assertTrue(latest.ok());
        }
    }

    @Test
    void anExceptionIsPublishedAndTheWorkerCarriesOn() throws Exception {
        final SolveInput bad = empty();
        final SolveInput good = empty();
        try (SolveService service = new SolveService(input -> {
            if (input == bad) throw new IllegalStateException("boom");
            return Balancer.solve(input);
        })) {
            service.submit(7, bad);
            assertTrue(service.awaitIdle(WAIT));
            final SolveService.Result failed = service.latest();
            assertNotNull(failed);
            assertEquals(7, failed.version());
            assertFalse(failed.ok());
            assertEquals(
                "boom",
                failed.error()
                    .getMessage());
            assertEquals(
                SolverMessage.SOLVE_CRASHED,
                failed.errorNote()
                    .message());

            service.submit(8, good);
            assertTrue(service.awaitIdle(WAIT));
            assertEquals(
                8,
                service.latest()
                    .version());
            assertTrue(
                service.latest()
                    .ok(),
                "the worker survived the exception");
        }
    }

    @Test
    void requestOnlySnapshotsWhenTheVersionMoves() throws Exception {
        final AtomicInteger snapshots = new AtomicInteger();
        try (SolveService service = new SolveService(Balancer::solve)) {
            assertTrue(service.request(5, () -> {
                snapshots.incrementAndGet();
                return empty();
            }));
            assertFalse(service.request(5, () -> {
                snapshots.incrementAndGet();
                return empty();
            }), "same version: nothing to do");
            assertTrue(service.request(6, () -> {
                snapshots.incrementAndGet();
                return empty();
            }));
            assertTrue(service.awaitIdle(WAIT));
            assertEquals(2, snapshots.get());
            assertEquals(
                6,
                service.latest()
                    .version());
        }
    }

    @Test
    void solvesARealChartOffTheCallingThread() throws Exception {
        final LoadedChart chart = GtnhFlowLoader.load("mk1");
        final BalanceResult direct = Balancer.balance(chart.graph(), BalanceMode.AUTO);
        final List<String> threads = Collections.synchronizedList(new ArrayList<>());
        final Function<SolveInput, BalanceResult> solver = input -> {
            threads.add(
                Thread.currentThread()
                    .getName());
            return Balancer.solve(input);
        };
        try (SolveService service = new SolveService(solver)) {
            service.request(
                chart.graph()
                    .solveVersion(),
                () -> SolveInput.of(chart.graph(), BalanceMode.AUTO, null));
            assertTrue(service.awaitIdle(WAIT));

            final SolveService.Result result = service.latest();
            assertNotNull(result);
            assertEquals(
                chart.graph()
                    .solveVersion(),
                result.version(),
                "the board can tell the answer is current");
            assertTrue(result.balance() instanceof BalanceResult.Solved);
            for (final Node machine : chart.machines()) {
                assertEquals(
                    direct.nodeBalances()
                        .get(machine.id)
                        .operations(),
                    result.balance()
                        .nodeBalances()
                        .get(machine.id)
                        .operations(),
                    1e-6);
            }
            assertEquals(List.of("GTNH Planner solver"), threads);
        }
    }

    @Test
    void closeStopsTakingWork() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final SolveService service = new SolveService(input -> {
            calls.incrementAndGet();
            return Balancer.solve(input);
        });
        service.close();
        service.submit(1, empty());
        assertTrue(service.awaitIdle(WAIT));
        assertEquals(0, calls.get());
        assertNull(service.latest());
    }

    private static SolveInput empty() {
        return new SolveInput(BalanceMode.NONE, null, List.of(), List.of(), List.of(), List.of(), Map.of());
    }

    private static void await(final CountDownLatch latch) {
        try {
            if (!latch.await(WAIT, TimeUnit.MILLISECONDS)) throw new AssertionError("latch timed out");
        } catch (final InterruptedException e) {
            throw new AssertionError(e);
        }
    }
}

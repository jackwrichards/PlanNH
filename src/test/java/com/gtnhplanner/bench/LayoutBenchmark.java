package com.gtnhplanner.bench;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.harness.GtnhFlowLoader;
import com.gtnhplanner.importer.FfImport;
import com.gtnhplanner.importer.FfPlan;
import com.gtnhplanner.layout.RouteMetrics;

/**
 * The layout benchmark: every plan in the corpus routed where the website left it, and arranged by Arrange and routed
 * again, each measured ({@link RouteMetrics}) and drawn to a PNG under {@code build/bench/<label>/}, with a summary
 * table. Run with {@code ./gradlew test -Pbench=<label> --tests '*LayoutBenchmark*'}; left out of the normal run.
 *
 * <p>
 * The corpus: the importer's fixtures, the plans a dev run kept from the Library, the website's examples, and the
 * gtnh-flow charts (no positions or drawers of their own, so arranged only).
 */
@Tag("bench")
class LayoutBenchmark {

    private record Row(String board, String mode, int boxes, RouteMetrics.Result m, double routeMs, double arrangeMs,
        int fellBack) {}

    @Test
    void benchmark() throws IOException {
        final String label = System.getProperty("gtnhplanner.bench", "run");
        final File out = new File("build/bench/" + (label.isEmpty() ? "run" : label));
        out.mkdirs();
        final String only = System.getProperty("gtnhplanner.bench.only", "");
        final List<BenchBoard> corpus = new ArrayList<>();
        for (final BenchBoard b : corpus()) if (only.isEmpty() || java.util.regex.Pattern.compile(only)
            .matcher(b.name())
            .find()) corpus.add(b);
        final List<Row> rows = new ArrayList<>();
        for (final BenchBoard board : corpus) {
            final boolean placedToo = board.items()
                .stream()
                .anyMatch(i -> i.x() != 0 || i.y() != 0);
            if (placedToo) {
                final BenchRun.Routed r = BenchRun.route(board);
                rows.add(row(board, "placed", r, 0));
                BenchRender.render(r, board.name() + " - as placed", new File(out, slug(board.name()) + "-placed.png"));
            }
            final double[] ms = new double[1];
            final BenchBoard arranged = BenchRun.arrange(board, ms);
            final BenchRun.Routed r = BenchRun.route(arranged);
            rows.add(row(board, "arranged", r, ms[0]));
            BenchRender.render(r, board.name() + " - arranged", new File(out, slug(board.name()) + "-arranged.png"));
        }
        final String table = table(rows);
        Files.writeString(new File(out, "summary.md").toPath(), table, StandardCharsets.UTF_8);
        System.out.println(table);
    }

    private static Row row(final BenchBoard b, final String mode, final BenchRun.Routed r, final double arrangeMs) {
        return new Row(
            b.name(),
            mode,
            b.items()
                .size(),
            r.metrics(),
            r.millis(),
            arrangeMs,
            r.fellBack()
                .size());
    }

    /** The columns: name, then how to read it off a row. */
    private static final String[] COLS = { "points", "crossings", "overlap", "bends", "sharp", "length", "excess",
        "backtrack", "jogs", "wrongSides", "boxHits", "sharedEnds", "route ms", "arrange ms", "fellBack" };

    private static double[] values(final Row r) {
        final RouteMetrics.Result m = r.m();
        return new double[] { m.points(), m.crossings(), m.overlap(), m.bends(), m.sharp(), m.length(), m.excess(),
            m.backtrack(), m.jogs(), m.wrongSides(), m.boxHits(), m.sharedEnds(), r.routeMs(), r.arrangeMs(),
            r.fellBack() };
    }

    private static String table(final List<Row> rows) {
        final StringBuilder s = new StringBuilder("| board | mode | boxes | wires |");
        for (final String c : COLS) s.append(' ')
            .append(c)
            .append(" |");
        s.append("\n|---|---|---|---|");
        for (int i = 0; i < COLS.length; i++) s.append("---|");
        s.append('\n');
        final double[][] totals = new double[2][COLS.length + 1];
        for (final Row r : rows) {
            s.append(
                String.format(
                    Locale.ROOT,
                    "| %s | %s | %d | %d |",
                    r.board(),
                    r.mode(),
                    r.boxes(),
                    r.m()
                        .wires()));
            final double[] v = values(r);
            for (int k = 0; k < v.length; k++)
                s.append(String.format(Locale.ROOT, k == 12 || k == 13 ? " %.1f |" : " %.0f |", v[k]));
            s.append('\n');
            final double[] t = totals[r.mode()
                .equals("placed") ? 0 : 1];
            for (int k = 0; k < v.length; k++) t[k] += v[k];
            t[COLS.length] += r.m()
                .wires();
        }
        for (int i = 0; i < 2; i++) {
            s.append(
                String.format(
                    Locale.ROOT,
                    "| **total** | %s | | %.0f |",
                    i == 0 ? "placed" : "arranged",
                    totals[i][COLS.length]));
            for (int k = 0; k < COLS.length; k++)
                s.append(String.format(Locale.ROOT, k == 12 || k == 13 ? " %.1f |" : " %.0f |", totals[i][k]));
            s.append('\n');
        }
        return s.toString();
    }

    private static String slug(final String s) {
        return s.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    /** Every board the corpus has, plans first. */
    static List<BenchBoard> corpus() throws IOException {
        final List<BenchBoard> boards = new ArrayList<>();
        for (final File dir : new File[] { new File("src/test/resources/factory-flow"),
            new File("run/client/library-downloads"), new File("../gtnh-factory-flow/examples"),
            new File("../gtnh-factory-flow/scratch/router-corpus") }) {
            final File[] files = dir
                .listFiles((d, n) -> n.endsWith(".json") && !n.endsWith(".capture.json") && !n.contains("-2.plan"));
            if (files == null) continue;
            java.util.Arrays.sort(files);
            for (final File f : files) {
                try {
                    final FfPlan plan = FfImport.read(Files.readString(f.toPath(), StandardCharsets.UTF_8));
                    final String name = plan.name() != null && !plan.name()
                        .isBlank() ? plan.name() : f.getName();
                    boards.add(BenchBoard.fromPlan(dir.getName() + "/" + name, plan));
                } catch (final RuntimeException e) {
                    System.out.println("skipped " + f + ": " + e);
                }
            }
        }
        for (final String chart : GtnhFlowLoader.CORPUS) boards.add(fromChart(chart));
        return boards;
    }

    /** A gtnh-flow chart: its machines as cards, wired as it is; no drawers, no positions. */
    private static BenchBoard fromChart(final String name) {
        final GtnhFlowLoader.LoadedChart chart = GtnhFlowLoader.load(name);
        final List<BenchBoard.Item> items = new ArrayList<>();
        for (final Node n : chart.graph()
            .getNodes()) {
            final int ins = n.inputs.size(), outs = n.outputs.size();
            items.add(
                new BenchBoard.Item(
                    n.id,
                    n.machineName == null ? "machine" : n.machineName,
                    false,
                    false,
                    0,
                    0,
                    BenchBoard.CARD_W,
                    BenchBoard.cardHeight(Math.max(ins + 1, outs)),
                    ins,
                    outs));
        }
        final List<BenchBoard.Link> links = new ArrayList<>();
        for (final Edge e : chart.graph()
            .getEdges())
            links.add(
                new BenchBoard.Link(
                    e.id,
                    e.sourceNodeId,
                    e.sourceOutputIndex,
                    e.targetNodeId,
                    e.targetInputIndex,
                    "r" + e.sourceOutputIndex));
        return new BenchBoard("gtnh-flow/" + name, items, links);
    }

    @SuppressWarnings("unused")
    private static UUID unused() {
        return null;
    }
}

package com.gtnhplanner.bench;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

/**
 * Draws a routed board to a PNG, so a layout can be looked at without the game: cards grey, drawers blue, wires in
 * their resource's colour, and what the metrics count marked on it: crossings red rings, runs through a box red,
 * runs away from where a wire is going orange.
 */
public final class BenchRender {

    private BenchRender() {}

    private static final int MAX_SIDE = 2200, MARGIN = 40, HEADER = 44;

    public static void render(final BenchRun.Routed r, final String title, final File out) throws IOException {
        render(r, title, out, null);
    }

    /** As {@link #render}, of just {@code crop} (x0, y0, x1, y1 in world px) when given, at up to full size. */
    public static void render(final BenchRun.Routed r, final String title, final File out, final int[] crop)
        throws IOException {
        final BenchBoard b = r.board();
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
        for (final BenchBoard.Item i : b.items()) {
            x0 = Math.min(x0, i.x());
            y0 = Math.min(y0, i.y());
            x1 = Math.max(x1, i.x() + i.w());
            y1 = Math.max(y1, i.y() + i.h());
        }
        for (final List<int[]> p : r.routes()
            .values()) for (final int[] q : p) {
                x0 = Math.min(x0, q[0]);
                y0 = Math.min(y0, q[1]);
                x1 = Math.max(x1, q[0]);
                y1 = Math.max(y1, q[1]);
            }
        if (crop != null) {
            x0 = crop[0];
            y0 = crop[1];
            x1 = crop[2];
            y1 = crop[3];
        }
        if (x0 > x1) return;
        final double scale = Math.min(crop != null ? 1.0 : 1.5, (MAX_SIDE - 2.0 * MARGIN) / Math.max(x1 - x0, y1 - y0));
        final int w = (int) ((x1 - x0) * scale) + 2 * MARGIN, h = (int) ((y1 - y0) * scale) + 2 * MARGIN + HEADER;
        final BufferedImage img = new BufferedImage(Math.max(w, 900), h, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x16171A));
        g.fillRect(0, 0, img.getWidth(), h);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        g.drawString(title, 12, 20);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        g.setColor(new Color(0xC8CAD0));
        g.drawString(
            r.metrics()
                .line()
                + String.format(
                    "  route %.1f ms  fellBack %d",
                    r.millis(),
                    r.fellBack()
                        .size()),
            12,
            38);
        final double fx0 = x0, fy0 = y0;
        final java.util.function.DoubleUnaryOperator X = v -> (v - fx0) * scale + MARGIN,
            Y = v -> (v - fy0) * scale + MARGIN + HEADER;
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(9, (int) (11 * scale))));
        for (final BenchBoard.Item i : b.items()) {
            final int bx = (int) X.applyAsDouble(i.x()), by = (int) Y.applyAsDouble(i.y()), bw = (int) (i.w() * scale),
                bh = (int) (i.h() * scale);
            g.setColor(i.drawer() ? new Color(0x1F3A52) : new Color(0x2A2C31));
            g.fillRect(bx, by, bw, bh);
            g.setColor(i.drawer() ? new Color(0x3E6E96) : new Color(0x55575E));
            g.drawRect(bx, by, bw, bh);
            g.setColor(new Color(0xD8DADF));
            final String label = i.label() == null ? "" : i.label();
            g.drawString(label.length() > 34 ? label.substring(0, 33) + "…" : label, bx + 4, by + 13);
        }
        final Map<UUID, BenchBoard.Item> byId = b.byId();
        g.setStroke(new BasicStroke((float) Math.max(1.5, 2 * scale), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (final BenchBoard.Link l : b.links()) {
            final List<int[]> p = r.routes()
                .get(l.id());
            if (p == null || p.size() < 2) continue;
            final Color c = colour(l.resource());
            final int[] s = p.get(0), t = p.get(p.size() - 1);
            final int gx = Integer.signum(t[0] - s[0]), gy = Integer.signum(t[1] - s[1]);
            for (int k = 0; k + 1 < p.size(); k++) {
                final int[] a = p.get(k), q = p.get(k + 1);
                final boolean back = (q[0] - a[0]) * gx < 0 || (q[1] - a[1]) * gy < 0;
                g.setColor(back ? new Color(0xFF9A3C) : c);
                g.drawLine(
                    (int) X.applyAsDouble(a[0]),
                    (int) Y.applyAsDouble(a[1]),
                    (int) X.applyAsDouble(q[0]),
                    (int) Y.applyAsDouble(q[1]));
            }
            // An arrowhead at the target.
            final int[] a = p.get(p.size() - 2);
            final double ang = Math.atan2(t[1] - a[1], t[0] - a[0]), tx = X.applyAsDouble(t[0]),
                ty = Y.applyAsDouble(t[1]), al = 7;
            g.setColor(c);
            g.fillPolygon(
                new int[] { (int) tx, (int) (tx - al * Math.cos(ang - 0.45)), (int) (tx - al * Math.cos(ang + 0.45)) },
                new int[] { (int) ty, (int) (ty - al * Math.sin(ang - 0.45)), (int) (ty - al * Math.sin(ang + 0.45)) },
                3);
        }
        // Crossings.
        g.setColor(new Color(0xFF4040));
        g.setStroke(new BasicStroke(1.5f));
        final List<List<int[]>> all = new java.util.ArrayList<>(
            r.routes()
                .values());
        for (int i = 0; i < all.size(); i++) for (int j = i + 1; j < all.size(); j++) {
            final List<int[]> p = all.get(i), q = all.get(j);
            for (int k = 0; k + 1 < p.size(); k++) for (int m = 0; m + 1 < q.size(); m++) {
                final double[] at = intersection(p.get(k), p.get(k + 1), q.get(m), q.get(m + 1));
                if (at != null) g.drawOval((int) X.applyAsDouble(at[0]) - 5, (int) Y.applyAsDouble(at[1]) - 5, 10, 10);
            }
        }
        g.dispose();
        out.getParentFile()
            .mkdirs();
        ImageIO.write(img, "png", out);
    }

    private static Color colour(final String resource) {
        final int h = resource == null ? 0 : resource.hashCode();
        return Color.getHSBColor((h & 0xFFFF) / 65535f, 0.55f, 0.95f);
    }

    /** Where two segments properly cross, or null. */
    private static double[] intersection(final int[] a, final int[] b, final int[] c, final int[] d) {
        final double rx = b[0] - a[0], ry = b[1] - a[1], sx = d[0] - c[0], sy = d[1] - c[1];
        final double den = rx * sy - ry * sx;
        if (den == 0) return null;
        final double t = ((c[0] - a[0]) * sy - (c[1] - a[1]) * sx) / den,
            u = ((c[0] - a[0]) * ry - (c[1] - a[1]) * rx) / den;
        if (t <= 0 || t >= 1 || u <= 0 || u >= 1) return null;
        return new double[] { a[0] + t * rx, a[1] + t * ry };
    }
}

package com.gtnhplanner.ui.canvas;

import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.widget.WidgetTree;
import com.cleanroommc.modularui.widget.sizer.Area;
import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.Resources;
import com.gtnhplanner.ui.card.RecipeCard;
import com.gtnhplanner.ui.theme.Fmt;
import com.gtnhplanner.ui.theme.Hyb;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * A picture of the open plan, for the Share key's Screenshot: the board as it is (nothing lit, selected or in hand) at
 * the size the plan needs, detailed or simple (the cards' zoomed-out glance view, and the names when the board shows
 * them); under it, when asked, a footer with the plan's name and what goes in and out; and a small "GTNH Planner" mark.
 * The board draws itself into a framebuffer of its own once the frame is done, at the game's GUI scale, and the
 * picture is read back to show, save or copy.
 */
public final class PlanPicture {

    /** How much the cards show: everything (detailed), or the zoomed-out board's machine and count (simple). */
    public enum Detail {
        DETAILED,
        SIMPLE
    }

    /** What the picture shows: its detail, and whether its footer gives the plan's name and what goes in and out. */
    public record Options(Detail detail, boolean name, boolean flows) {}

    /** The board's zoom for each detail: full size, and well inside the cards' glance view. */
    private static final float FULL_ZOOM = 1f, OUT_ZOOM = 0.4f;
    /** Room round the plan, in board units. */
    private static final int MARGIN = 40;
    /** The largest picture: a side, and pixels in all. A big plan gets fewer pixels first, then a smaller board. */
    private static final int MAX_SIDE = 8192;
    private static final long MAX_PIXELS = 40_000_000L;
    /** The narrowest picture, in GUI units: a small plan's, and wider with a footer so its panels have room. */
    private static final int MIN_WIDTH = 240, MIN_WIDTH_FOOTER = 480;

    private static boolean drawing;

    private PlanPicture() {}

    /** Whether the board is drawing into a picture now: it then draws as it is, nothing lit, moving or selected. */
    public static boolean drawing() {
        return drawing;
    }

    // region Taking it

    private record Job(BoardCanvas canvas, Options options, Consumer<BufferedImage> done, Consumer<String> failed) {}

    @Nullable
    private static Job queued;
    private static boolean ticking;

    /**
     * Takes a picture once this frame is done; {@code done} gets it, or {@code failed} why not, on the client thread.
     * A newer request replaces one not taken yet.
     */
    public static void take(final BoardCanvas canvas, final Options options, final Consumer<BufferedImage> done,
        final Consumer<String> failed) {
        queued = new Job(canvas, options, done, failed);
        if (!ticking) {
            ticking = true;
            FMLCommonHandler.instance()
                .bus()
                .register(new Ticker());
        }
    }

    /** Takes the picture asked for when the frame is done, where nothing on screen sees the board drawn again. */
    public static final class Ticker {

        @SubscribeEvent
        public void onRenderTick(final TickEvent.RenderTickEvent event) {
            final Job job = queued;
            if (event.phase != TickEvent.Phase.END || job == null) return;
            // A board laid out afresh this frame is drawn next frame.
            if (job.canvas()
                .requiresResize()) return;
            queued = null;
            final BufferedImage image;
            try {
                image = render(job.canvas(), job.options());
            } catch (final RuntimeException | OutOfMemoryError e) {
                GtnhPlanner.LOG.warn("Could not take a picture of the plan", e);
                job.failed()
                    .accept(e instanceof OutOfMemoryError ? "The plan is too big for a picture" : reason(e));
                return;
            }
            job.done()
                .accept(image);
        }
    }

    private static BufferedImage render(final BoardCanvas canvas, final Options o) {
        final BoardSession session = canvas.session();
        final Graph g = session.graph();
        final int maxSide = Math.min(MAX_SIDE, GL11.glGetInteger(GL30.GL_MAX_RENDERBUFFER_SIZE));
        // Pixels per GUI unit: the game's own GUI scale, so the picture looks as the board does. A plan too big for
        // that gets fewer, then (at one) a smaller board.
        int scale = guiScale();
        float zoom = o.detail() == Detail.DETAILED ? FULL_ZOOM : OUT_ZOOM;
        int boardW, boardH, width, height;
        float[] b;
        Footer footer;
        while (true) {
            // The names zoomed out are as big on screen at any zoom, so they take more of the board the smaller it is.
            b = canvas.planBounds(zoom);
            if (b == null) throw new IllegalStateException("There is nothing on the board yet");
            final float worldW = b[2] - b[0] + 2 * MARGIN, worldH = b[3] - b[1] + 2 * MARGIN;
            boardW = (int) Math.ceil(worldW * zoom);
            boardH = (int) Math.ceil(worldH * zoom);
            width = Math.max(boardW, o.name() || o.flows() ? MIN_WIDTH_FOOTER : MIN_WIDTH);
            footer = o.name() || o.flows() ? new Footer(session, o, width) : null;
            height = boardH + (footer == null ? 0 : footer.height());
            final long pw = (long) width * scale, ph = (long) height * scale;
            if (pw <= maxSide && ph <= maxSide && pw * ph <= MAX_PIXELS) break;
            if (scale > 1) scale--;
            else zoom *= 0.8f;
        }
        final int pw = width * scale, ph = height * scale;

        final int previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        final int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        final int previousRenderbuffer = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
        final IntBuffer viewport = BufferUtils.createIntBuffer(16);
        GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
        final float zoomBefore = g.getZoom(), panXBefore = g.getPanX(), panYBefore = g.getPanY();
        final Area area = canvas.getArea();
        final int[] areaBefore = { area.x, area.y, area.rx, area.ry, area.width, area.height };
        int fbo = 0, color = 0, depth = 0;
        boolean attribs = false, matrices = false;
        try {
            color = GL30.glGenRenderbuffers();
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, color);
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL11.GL_RGBA8, pw, ph);
            // Depth for the items, stencil for the board's clipping.
            depth = GL30.glGenRenderbuffers();
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depth);
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH24_STENCIL8, pw, ph);
            // Raw GL, as the structure pictures: under Angelica, Minecraft's Framebuffer can silently not bind.
            fbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            if (GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING) != fbo)
                throw new IllegalStateException("the framebuffer bind did not take");
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_RENDERBUFFER, color);
            GL30.glFramebufferRenderbuffer(
                GL30.GL_FRAMEBUFFER,
                GL30.GL_DEPTH_STENCIL_ATTACHMENT,
                GL30.GL_RENDERBUFFER,
                depth);
            final int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("the framebuffer is incomplete: 0x" + Integer.toHexString(status));

            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            attribs = true;
            // The GUI's own projection, over the picture: GUI units across, as many pixels to one as the scale says.
            GL11.glViewport(0, 0, pw, ph);
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPushMatrix();
            GL11.glLoadIdentity();
            GL11.glOrtho(0, width, height, 0, 1000, 3000);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
            GL11.glLoadIdentity();
            GL11.glTranslatef(0, 0, -2000);
            matrices = true;
            GL11.glColorMask(true, true, true, true);
            GL11.glDepthMask(true);
            GL11.glClearColor(
                (Hyb.CANVAS >> 16 & 0xFF) / 255f,
                (Hyb.CANVAS >> 8 & 0xFF) / 255f,
                (Hyb.CANVAS & 0xFF) / 255f,
                1f);
            GL11.glClearDepth(1);
            GL11.glClearStencil(0);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);

            // The board, drawn by itself: the camera framing the plan, the canvas as big as the picture's board.
            g.setZoom(zoom);
            g.setPanX(-(b[0] - MARGIN) * zoom);
            g.setPanY(-(b[1] - MARGIN) * zoom);
            area.x = area.rx = (width - boardW) / 2;
            area.y = area.ry = 0;
            area.width = boardW;
            area.height = boardH;
            final ModularGuiContext context = canvas.getContext();
            context.pushMatrix();
            context.resetCurrent();
            drawing = true;
            try {
                WidgetTree.drawTree(canvas, context, true, true);
            } finally {
                drawing = false;
                context.popMatrix();
            }

            // Under it the footer, and the mark: over the board's corner, or in the footer.
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glColor4f(1, 1, 1, 1);
            if (footer != null) footer.draw(boardH);
            else mark(width, boardH);

            return read(pw, ph);
        } catch (final RuntimeException | Error e) {
            // A throw mid-draw can leave a tessellator batch open, which would break the game's next draw: flush it
            // into the throwaway framebuffer ("Not tesselating!" just means there was none).
            try {
                net.minecraft.client.renderer.Tessellator.instance.draw();
            } catch (final RuntimeException ignored) {}
            throw e;
        } finally {
            drawing = false;
            g.setZoom(zoomBefore);
            g.setPanX(panXBefore);
            g.setPanY(panYBefore);
            area.x = areaBefore[0];
            area.y = areaBefore[1];
            area.rx = areaBefore[2];
            area.ry = areaBefore[3];
            area.width = areaBefore[4];
            area.height = areaBefore[5];
            if (matrices) {
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glPopMatrix();
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glPopMatrix();
            }
            if (attribs) GL11.glPopAttrib();
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, previousRenderbuffer);
            GL11.glViewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
            if (fbo != 0) GL30.glDeleteFramebuffers(fbo);
            if (color != 0) GL30.glDeleteRenderbuffers(color);
            if (depth != 0) GL30.glDeleteRenderbuffers(depth);
        }
    }

    /** The framebuffer as an image, top row first (GL's rows run bottom-up). */
    private static BufferedImage read(final int pw, final int ph) {
        final ByteBuffer bytes = BufferUtils.createByteBuffer(pw * ph * 4);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
        GL11.glReadPixels(0, 0, pw, ph, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, bytes);
        final IntBuffer pixels = bytes.asIntBuffer();
        final BufferedImage image = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_RGB);
        final int[] out = ((DataBufferInt) image.getRaster()
            .getDataBuffer()).getData();
        for (int y = 0; y < ph; y++) {
            pixels.position(y * pw);
            pixels.get(out, (ph - 1 - y) * pw, pw);
        }
        return image;
    }

    private static int guiScale() {
        final Minecraft mc = Minecraft.getMinecraft();
        return new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
    }

    // endregion

    // region The mark and the footer

    private static final String MARK = "GTNH Planner";

    /** The mark on the board's bottom right, small but growing with the picture so it still reads. */
    private static void mark(final int width, final int boardH) {
        final int k = Math.max(1, Math.round(Math.min(width, boardH) / 320f));
        final float x = width - (Hyb.width(MARK) + 6) * k, y = boardH - 14 * k;
        Hyb.text(MARK, x, y, k, 0x99C8CAD0, false);
    }

    /**
     * What goes under the board, as the website's export bar: a title row (the plan's icon and name large on the left;
     * its machines and power, and the mark, on the right), then what comes in and what goes out as two tinted panels
     * side by side, each its resources in even columns of icon, name and rate. Laid out at a design width, then drawn a
     * whole number of times larger, so it grows with a big picture instead of shrinking to nothing in it.
     */
    private static final class Footer {

        private static final int DESIGN = 900, PAD = 10, TITLE_H = 26, GAP = 8, INSET = 6, HEAD_H = 16, ROW_H = 18,
            COLUMN = 200, COLUMN_GAP = 14, MARK_H = 14;
        private static final int PLATE = 0xFF131417, EDGE = 0xFF2A2D33, BRAND = 0xFF22D3EE;
        private static final int IN_FILL = 0x1AF87171, IN_EDGE = 0x55F87171, OUT_FILL = 0x1A34D399,
            OUT_EDGE = 0x5534D399;

        /** One resource's row: its line in the overview and its rate as written. */
        private record Row(BoardSession.TotalLine line, String rate) {}

        /** GUI units to a design unit; the footer's width and height in design units. */
        private final int k, width, designH;
        @Nullable
        private final String name, face, summary;
        @Nullable
        private final List<Row> ins, outs;
        /** Columns in each panel, and the panels' height. */
        private final int columns, panelH;

        Footer(final BoardSession session, final Options o, final int guiWidth) {
            k = Math.max(1, guiWidth / DESIGN);
            width = guiWidth / k;
            final Graph g = session.graph();
            final BoardSession.Totals totals = session.totals();
            name = o.name() ? g.getName() : null;
            face = o.name() ? com.gtnhplanner.ui.library.AccountForms.faceKey(g) : null;
            summary = o.name() ? summary(totals) : null;
            int h = PAD;
            if (name != null) h += TITLE_H;
            if (o.flows()) {
                final Fmt.RateUnit unit = session.rateUnit();
                ins = rows(totals.inputs(), unit);
                outs = rows(totals.outputs(), unit);
                final int panelW = (width - 2 * PAD - GAP) / 2;
                columns = Math.max(1, (panelW - 2 * INSET + COLUMN_GAP) / (COLUMN + COLUMN_GAP));
                final int most = Math.max(1, Math.max(ins.size(), outs.size()));
                panelH = HEAD_H + (most + columns - 1) / columns * ROW_H + INSET;
                if (name != null) h += GAP;
                h += panelH;
                // The mark goes in the title row; without one, under the panels.
                if (name == null) h += MARK_H;
            } else {
                ins = outs = null;
                columns = 1;
                panelH = 0;
            }
            designH = h + PAD;
        }

        /** Its height in GUI units. */
        int height() {
            return designH * k;
        }

        /** How many machines it takes and the power it draws, as the overview counts them. */
        private static String summary(final BoardSession.Totals totals) {
            long machines = 0;
            for (final BoardSession.MachineLine m : totals.machines())
                machines += (long) Math.ceil(m.machines() - 1e-9);
            final String count = machines + (machines == 1 ? " machine" : " machines");
            return totals.euPerTick() > 0 ? count + "  ·  " + Fmt.power(totals.euPerTick()) + " EU/t" : count;
        }

        /** The resources that move, busiest first as the overview has them; none at nothing. */
        private static List<Row> rows(final List<BoardSession.TotalLine> totals, final Fmt.RateUnit unit) {
            final List<Row> rows = new ArrayList<>();
            for (final BoardSession.TotalLine t : totals) {
                if (t.amount() <= 0) continue;
                rows.add(
                    new Row(
                        t,
                        Resources.isPower(t.key()) ? Fmt.compact(t.amount() / 20) + " EU/t"
                            : Fmt.rate(t.amount(), unit, t.isFluid())));
            }
            return rows;
        }

        void draw(final int top) {
            GL11.glPushMatrix();
            GL11.glTranslatef(0, top, 0);
            GL11.glScalef(k, k, 1);
            Hyb.rect(0, 0, width, designH, PLATE);
            Hyb.rect(0, 0, width, 1, EDGE);
            int y = PAD;
            if (name != null) {
                // On the right: the plan's size and power, the mark under them.
                final int rightW = Math.max(Hyb.width(summary), Hyb.width(MARK));
                Hyb.textRight(summary, width - PAD, y + 2, Hyb.MUTED);
                Hyb.text(MARK, width - PAD - Hyb.width(MARK), y + 14, 1f, BRAND, false);
                // On the left: the icon and the name, large.
                int x = PAD;
                if (face != null) {
                    icon(face, x, y, 24);
                    x += 30;
                }
                Hyb.text(Hyb.fit(name, (width - x - PAD - rightW - 12) / 2), x, y + 5, 2f, Hyb.INK);
                y += TITLE_H;
            }
            if (ins != null) {
                if (name != null) y += GAP;
                final int panelW = (width - 2 * PAD - GAP) / 2;
                panel("INPUTS", Hyb.SOURCE_INK, IN_FILL, IN_EDGE, ins, PAD, y, panelW, "Nothing comes in");
                panel(
                    "OUTPUTS",
                    Hyb.PRODUCT_INK,
                    OUT_FILL,
                    OUT_EDGE,
                    outs,
                    PAD + panelW + GAP,
                    y,
                    width - 2 * PAD - GAP - panelW,
                    "Nothing goes out");
                y += panelH;
                if (name == null) Hyb.text(MARK, width - PAD - Hyb.width(MARK), y + 5, 1f, BRAND, false);
            }
            GL11.glPopMatrix();
        }

        /** One side: a tinted panel, its heading, and its resources down even columns. */
        private void panel(final String head, final int ink, final int fill, final int edge, final List<Row> rows,
            final int x, final int y, final int w, final String none) {
            Hyb.rect(x, y, w, panelH, edge);
            Hyb.rect(x + 1, y + 1, w - 2, panelH - 2, PLATE);
            Hyb.rect(x + 1, y + 1, w - 2, panelH - 2, fill);
            Hyb.text(head, x + INSET, y + 5, ink);
            if (rows.isEmpty()) {
                Hyb.text(none, x + INSET, y + HEAD_H + 5, Hyb.MUTED);
                return;
            }
            final int perColumn = (rows.size() + columns - 1) / columns;
            final int columnW = (w - 2 * INSET - (columns - 1) * COLUMN_GAP) / columns;
            for (int i = 0; i < rows.size(); i++) {
                final Row r = rows.get(i);
                final int cx = x + INSET + i / perColumn * (columnW + COLUMN_GAP);
                final int ry = y + HEAD_H + i % perColumn * ROW_H;
                if (Resources.isPower(
                    r.line()
                        .key()))
                    RecipeCard.euIcon(cx, ry + 1, 16);
                else Hyb.icon(
                    r.line()
                        .item(),
                    r.line()
                        .fluid(),
                    cx,
                    ry + 1,
                    16,
                    0);
                final int rateW = Hyb.width(r.rate());
                Hyb.textRight(r.rate(), cx + columnW, ry + 5, ink);
                Hyb.text(
                    Hyb.fit(
                        r.line()
                            .label(),
                        columnW - 20 - rateW - 6),
                    cx + 20,
                    ry + 5,
                    Hyb.INK);
            }
        }

        private static void icon(final String key, final int x, final int y, final int size) {
            final FluidStack fluid = Resources.fluid(key);
            final ItemStack item = fluid == null ? Resources.item(key) : null;
            Hyb.icon(item, fluid, x, y, size, 0);
        }
    }

    // endregion

    // region Keeping it: a preview, the screenshots folder, the clipboard

    private static final ExecutorService FILES = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "GTNH Planner pictures");
        t.setDaemon(true);
        return t;
    });

    /**
     * The picture made small to fit {@code w} by {@code h} pixels (never larger than it is), smoothly, in the
     * background; {@code done} gets it on the client thread.
     */
    public static void preview(final BufferedImage image, final int w, final int h,
        final Consumer<BufferedImage> done) {
        FILES.execute(() -> {
            final double f = Math.min(1, Math.min(w / (double) image.getWidth(), h / (double) image.getHeight()));
            final int tw = Math.max(1, (int) Math.round(image.getWidth() * f)),
                th = Math.max(1, (int) Math.round(image.getHeight() * f));
            // Halved a step at a time, then to size: one big step would skip most of the pixels.
            BufferedImage at = image;
            while (at.getWidth() / 2 >= tw && at.getHeight() / 2 >= th)
                at = scaled(at, at.getWidth() / 2, at.getHeight() / 2);
            final BufferedImage small = at.getWidth() == tw && at.getHeight() == th ? at : scaled(at, tw, th);
            onClient(() -> done.accept(small));
        });
    }

    private static BufferedImage scaled(final BufferedImage from, final int w, final int h) {
        final BufferedImage to = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        final java.awt.Graphics2D g = to.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(from, 0, 0, w, h, null);
        g.dispose();
        return to;
    }

    /** Saves the picture as a PNG in the game's screenshots folder, named for the plan; {@code done} gets the file. */
    public static void save(final BufferedImage image, final String planName, final Consumer<File> done,
        final Consumer<String> failed) {
        final File dir = new File(Minecraft.getMinecraft().mcDataDir, "screenshots");
        final String base = planName.replaceAll("[^A-Za-z0-9 _-]", "")
            .trim()
            .replace(' ', '_');
        final File file = new File(
            dir,
            (base.isEmpty() ? "plan" : base) + "_"
                + new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new Date())
                + ".png");
        FILES.execute(() -> {
            try {
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("could not make " + dir);
                ImageIO.write(image, "png", file);
                onClient(() -> done.accept(file));
            } catch (final IOException | RuntimeException e) {
                GtnhPlanner.LOG.warn("Could not save the picture of the plan", e);
                onClient(() -> failed.accept(reason(e)));
            }
        });
    }

    /** Puts the picture on the clipboard, to paste into a chat. */
    public static void copy(final BufferedImage image, final Runnable done, final Consumer<String> failed) {
        FILES.execute(() -> {
            try {
                Toolkit.getDefaultToolkit()
                    .getSystemClipboard()
                    .setContents(new Picture(image), null);
                onClient(done);
            } catch (final RuntimeException | Error e) {
                GtnhPlanner.LOG.warn("Could not copy the picture of the plan", e);
                onClient(() -> failed.accept(reason(e)));
            }
        });
    }

    /** A picture as the clipboard takes one. */
    private record Picture(BufferedImage image) implements Transferable {

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[] { DataFlavor.imageFlavor };
        }

        @Override
        public boolean isDataFlavorSupported(final DataFlavor flavor) {
            return DataFlavor.imageFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(final DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) throw new UnsupportedFlavorException(flavor);
            return image;
        }
    }

    // endregion

    private static String reason(final Throwable e) {
        return e.getMessage() == null ? e.getClass()
            .getSimpleName() : e.getMessage();
    }

    private static void onClient(final Runnable r) {
        Minecraft.getMinecraft()
            .func_152344_a(r);
    }
}

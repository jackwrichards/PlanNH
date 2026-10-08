package com.gtnhplanner.dev;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import net.minecraft.client.Minecraft;

import org.lwjgl.BufferUtils;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Records the game's own frames for demo videos: {@code call 'record?start=name&fps=24&width=1280'} begins writing
 * JPEG frames to {@code run/client/recordings/<name>/}, {@code call 'record?stop=1'} ends it and reports the count.
 * Frames are read from the back buffer after each drawn frame, so other windows never get in the way; while a screen
 * is open a cursor is drawn where the mouse is, since the system's is not in the frame. Frames are dropped, never
 * queued without end, when writing falls behind. Turn them into a video with ffmpeg at the same fps.
 */
public final class DevRecorder {

    static final DevRecorder INSTANCE = new DevRecorder();

    private record Frame(ByteBuffer pixels, int w, int h, int mouseX, int mouseY, boolean cursor, int index) {}

    private volatile File dir;
    private long frameNanos, last;
    private int outWidth;
    private final AtomicInteger index = new AtomicInteger(), dropped = new AtomicInteger();
    private final BlockingQueue<ByteBuffer> free = new ArrayBlockingQueue<>(8);
    private ExecutorService writers;
    private int bufferSize;

    private DevRecorder() {}

    Map<String, Object> start(final String name, final int fps, final int width) {
        stop();
        final File d = new File(Minecraft.getMinecraft().mcDataDir, "recordings/" + name);
        d.mkdirs();
        final File[] old = d.listFiles();
        if (old != null) for (final File f : old) f.delete();
        frameNanos = 1_000_000_000L / Math.max(1, fps);
        outWidth = width;
        index.set(0);
        dropped.set(0);
        free.clear();
        bufferSize = 0;
        writers = Executors.newFixedThreadPool(3);
        last = 0;
        dir = d;
        return Map.of("recording", d.getAbsolutePath(), "fps", fps);
    }

    Map<String, Object> stop() {
        final File d = dir;
        dir = null;
        if (writers != null) {
            writers.shutdown();
            try {
                writers.awaitTermination(20, TimeUnit.SECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread()
                    .interrupt();
            }
            writers = null;
        }
        return d == null ? Map.of("recording", false)
            : Map.of("frames", index.get(), "dropped", dropped.get(), "dir", d.getAbsolutePath());
    }

    @SubscribeEvent
    public void onRender(final TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || dir == null) return;
        final long now = System.nanoTime();
        if (last != 0 && now - last < frameNanos) return;
        last = last == 0 ? now : last + frameNanos * Math.max(1, (now - last) / frameNanos);
        final Minecraft mc = Minecraft.getMinecraft();
        final int w = mc.displayWidth, h = mc.displayHeight;
        if (bufferSize != w * h * 3) {
            free.clear();
            bufferSize = w * h * 3;
            for (int i = 0; i < 6; i++) free.offer(BufferUtils.createByteBuffer(bufferSize));
        }
        final ByteBuffer buf = free.poll();
        if (buf == null) {
            dropped.incrementAndGet();
            return;
        }
        buf.clear();
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, w, h, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, buf);
        final Frame f = new Frame(
            buf,
            w,
            h,
            Mouse.getX(),
            Mouse.getY(),
            mc.currentScreen != null,
            index.getAndIncrement());
        final File d = dir;
        writers.execute(() -> write(f, d));
    }

    private void write(final Frame f, final File d) {
        try {
            final BufferedImage full = new BufferedImage(f.w, f.h, BufferedImage.TYPE_INT_RGB);
            final int[] row = new int[f.w];
            for (int y = 0; y < f.h; y++) {
                int p = (f.h - 1 - y) * f.w * 3;
                for (int x = 0; x < f.w; x++, p += 3)
                    row[x] = (f.pixels.get(p) & 0xFF) << 16 | (f.pixels.get(p + 1) & 0xFF) << 8
                        | f.pixels.get(p + 2) & 0xFF;
                full.setRGB(0, y, f.w, 1, row, 0, f.w);
            }
            free.offer(f.pixels);
            final int ow = Math.min(outWidth, f.w), oh = f.h * ow / f.w;
            final BufferedImage out = new BufferedImage(ow, oh, BufferedImage.TYPE_INT_RGB);
            final Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(full, 0, 0, ow, oh, null);
            if (f.cursor) cursor(g, f.mouseX * ow / (float) f.w, (f.h - f.mouseY) * oh / (float) f.h, ow / 1280f);
            g.dispose();
            final ImageWriter jpg = ImageIO.getImageWritersByFormatName("jpg")
                .next();
            final ImageWriteParam p = jpg.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(0.92f);
            try (ImageOutputStream os = ImageIO
                .createImageOutputStream(new File(d, String.format("f%05d.jpg", f.index)))) {
                jpg.setOutput(os);
                jpg.write(null, new IIOImage(out, null, null), p);
            } finally {
                jpg.dispose();
            }
        } catch (final Exception e) {
            free.offer(f.pixels);
        }
    }

    /** An arrow pointer at the mouse, white with a dark edge. */
    private static void cursor(final Graphics2D g, final float x, final float y, final float s) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        final int[] px = { 0, 0, 4, 7, 9, 6, 11 }, py = { 0, 16, 12, 18, 17, 11, 11 };
        final Polygon p = new Polygon();
        for (int i = 0; i < px.length; i++)
            p.addPoint(Math.round(x + px[i] * 1.4f * s), Math.round(y + py[i] * 1.4f * s));
        g.setColor(Color.WHITE);
        g.fillPolygon(p);
        g.setColor(new Color(20, 20, 20));
        g.setStroke(new BasicStroke(1.2f * s));
        g.drawPolygon(p);
    }
}

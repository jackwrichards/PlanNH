package com.gtnhplanner.dev;

import java.util.List;

import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.opengl.GL11;

import com.gtnhplanner.ui.card.CleanCardView;
import com.gtnhplanner.ui.theme.Hyb;
import com.gtnhplanner.ui.world.PlanSnapshot;

/**
 * The plan last open's cards as the minimap and the world draw them, two columns on the board's canvas, to look
 * through. Opened with {@code call 'cards?from=0&count=3&scale=0.8&match=blast'} ({@code match}
 * keeps the cards whose machine's name has it); the arrow keys page through them; Esc closes it.
 */
public final class CardGallery extends GuiScreen {

    private int from;
    private final int count;
    /** How many cards matched, last drawn. */
    private int matched;
    private final float scale;
    /** Only the cards whose machine's name has this, any case; empty for all. */
    private final String match;

    CardGallery(final int from, final int count, final float scale, final String match) {
        this.from = from;
        this.count = count;
        this.scale = scale;
        this.match = match.toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public void drawScreen(final int mouseX, final int mouseY, final float partialTicks) {
        Hyb.rect(0, 0, width, height, Hyb.CANVAS);
        final PlanSnapshot snap = PlanSnapshot.latest();
        if (snap == null) {
            Hyb.textCentered("Open the planner once to publish a plan", width / 2f, height / 2f, Hyb.MUTED);
            return;
        }
        final List<PlanSnapshot.Card> cards = new java.util.ArrayList<>();
        for (final PlanSnapshot.Card c : snap.cards()) if (c.name()
            .toLowerCase(java.util.Locale.ROOT)
            .contains(match)) cards.add(c);
        matched = cards.size();
        final float cardW = CleanCardView.width() * scale, gap = 24;
        final float left = (width - 2 * cardW - gap) / 2f, right = left + cardW + gap;
        final String page = "Cards " + (Math.min(from, cards.size()) + 1)
            + "-"
            + Math.min(cards.size(), from + count)
            + " of "
            + cards.size()
            + "   (arrow keys, Esc)";
        Hyb.text(page, left, 6, Hyb.MUTED);
        // Two columns, left then right, a row at a time.
        float y = 18;
        for (int i = from; i < Math.min(cards.size(), from + count); i += 2) {
            final PlanSnapshot.Card a = cards.get(i);
            final PlanSnapshot.Card b = i + 1 < Math.min(cards.size(), from + count) ? cards.get(i + 1) : null;
            card(left, y, () -> CleanCardView.draw(a, snap.rateUnit(), false));
            if (b != null) card(right, y, () -> CleanCardView.draw(b, snap.rateUnit(), false));
            y += Math.max(CleanCardView.height(a), b == null ? 0 : CleanCardView.height(b)) * scale + 10;
        }
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    private void card(final float x, final float y, final Runnable draw) {
        GL11.glPushMatrix();
        GL11.glTranslatef(Math.round(x), Math.round(y), 0);
        GL11.glScalef(scale, scale, 1);
        draw.run();
        GL11.glPopMatrix();
    }

    /** Up and down step a card; left and right a page. */
    @Override
    protected void keyTyped(final char c, final int key) {
        final int step = switch (key) {
            case org.lwjgl.input.Keyboard.KEY_DOWN -> 1;
            case org.lwjgl.input.Keyboard.KEY_UP -> -1;
            case org.lwjgl.input.Keyboard.KEY_RIGHT -> count;
            case org.lwjgl.input.Keyboard.KEY_LEFT -> -count;
            default -> 0;
        };
        if (step != 0) from = Math.max(0, Math.min(Math.max(0, matched - count), from + step));
        else super.keyTyped(c, key);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}

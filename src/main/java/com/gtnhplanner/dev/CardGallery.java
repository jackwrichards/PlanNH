package com.gtnhplanner.dev;

import java.util.List;

import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.opengl.GL11;

import com.gtnhplanner.ui.card.CleanCardView;
import com.gtnhplanner.ui.card.PlanCardView;
import com.gtnhplanner.ui.theme.Hyb;
import com.gtnhplanner.ui.world.PlanSnapshot;

/**
 * Card designs side by side, to compare: each card of the plan last open, today's card on the left and the trial on the
 * right, on the board's canvas. Opened with {@code call 'cards?from=0&count=3&scale=0.8&match=blast'} ({@code match}
 * keeps the cards whose machine's name has it); Esc closes it.
 */
public final class CardGallery extends GuiScreen {

    private final int from, count;
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
        final float cardW = PlanCardView.width() * scale, gap = 24;
        final float left = (width - 2 * cardW - gap) / 2f, right = left + cardW + gap;
        Hyb.text("Now", left, 6, Hyb.MUTED);
        Hyb.text("Clean", right, 6, Hyb.MUTED);
        float y = 18;
        for (int i = from; i < Math.min(cards.size(), from + count); i++) {
            final PlanSnapshot.Card c = cards.get(i);
            card(left, y, () -> PlanCardView.draw(c, snap.rateUnit(), false));
            card(right, y, () -> CleanCardView.draw(c, snap.rateUnit(), false));
            y += Math.max(PlanCardView.height(c), CleanCardView.height(c)) * scale + 10;
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

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}

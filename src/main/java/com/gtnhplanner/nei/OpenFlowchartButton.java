package com.gtnhplanner.nei;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.screen.GuiContainerWrapper;
import com.gtnhplanner.GtnhPlanner;

import codechicken.nei.Button;

/** GTNH Planner's key on NEI's bar: the logo; a click opens the planner, or closes it back to where you were. */
public class OpenFlowchartButton extends Button {

    private static final ResourceLocation LOGO = new ResourceLocation(GtnhPlanner.MODID, "textures/gui/logo.png");

    public OpenFlowchartButton() {
        super("");
    }

    @Nullable
    private GuiScreen previousScreen = null;

    @Override
    public boolean onButtonPress(final boolean rightclick) {
        if (!rightclick) {
            final Minecraft mc = Minecraft.getMinecraft();
            if (mc.currentScreen instanceof GuiContainerWrapper) {
                mc.displayGuiScreen(previousScreen != null ? previousScreen : new GuiInventory(mc.thePlayer));
            } else {
                previousScreen = mc.currentScreen;
                com.gtnhplanner.ui.Planner.open();
            }
            return true;
        }
        return false;
    }

    private static final int BUTTON_WIDTH = 20;

    @Override
    public int contentWidth() {
        return BUTTON_WIDTH;
    }

    /** The key as last drawn, and when: the tour points at it. */
    private static OpenFlowchartButton shown;
    private static long shownAt;

    /** The key, when it was drawn in the last moment (an inventory with NEI is open); else null. */
    @Nullable
    public static OpenFlowchartButton shown() {
        return System.currentTimeMillis() - shownAt < 300 ? shown : null;
    }

    /** NEI's key, then the logo on it. */
    @Override
    public void draw(final int mouseX, final int mouseY) {
        shown = this;
        shownAt = System.currentTimeMillis();
        super.draw(mouseX, mouseY);
        // 12 GUI pixels for the 24-pixel logo: one to one at GUI scale 2, a pixel clear of the key's edges.
        final int size = 12;
        final int x = this.x + (w - size) / 2, y = this.y + (h - size) / 2;
        Minecraft.getMinecraft()
            .getTextureManager()
            .bindTexture(LOGO);
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        final Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.addVertexWithUV(x, y + size, 0, 0, 1);
        t.addVertexWithUV(x + size, y + size, 0, 1, 1);
        t.addVertexWithUV(x + size, y, 0, 1, 0);
        t.addVertexWithUV(x, y, 0, 0, 0);
        t.draw();
    }

    @Override
    public void addTooltips(final List<String> tooltip) {
        tooltip.add("GTNH Planner");
        tooltip.add("§7Click: open the planner, or close it");
    }
}

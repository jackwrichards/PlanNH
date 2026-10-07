package com.gtnhplanner.nei;

import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.input.Keyboard;

import com.cleanroommc.modularui.screen.GuiContainerWrapper;
import com.gtnhplanner.Compat;
import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.Tags;

import codechicken.nei.NEIClientUtils;
import codechicken.nei.api.API;
import codechicken.nei.api.IConfigureNEI;
import codechicken.nei.config.OptionCycled;
import codechicken.nei.config.OptionIntegerField;
import codechicken.nei.guihook.GuiContainerManager;
import codechicken.nei.recipe.GuiOverlayButton;
import codechicken.nei.recipe.GuiRecipe;
import codechicken.nei.recipe.GuiRecipeButton;
import codechicken.nei.recipe.GuiRecipeButton.UpdateRecipeButtonsEvent;
import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.RecipeHandlerRef;
import codechicken.nei.recipe.RecipeInfo;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

public class NEIPlanConfig implements IConfigureNEI {

    public static class ConfigItemColumns {

        public static String KEY = "gtnhplanner.item_columns";
        public static int min = 6;
        public static int max = Integer.MAX_VALUE;
        public static int defVal = 9;
    }

    public static class ConfigBurnableOverride {

        public static String KEY = "gtnhplanner.burnable_override";
        public static int OFF = 0;
        public static int ON = 1;
    }

    // Hash binds carry their modifiers in the stored int and compare exactly, so every accepted
    // combination needs its own ident.
    public static class ConfigUndoKey {

        public static String KEY = "gtnhplanner.undo";
        public static int defVal = Keyboard.KEY_Z + NEIClientUtils.CTRL_HASH;
    }

    public static class ConfigRedoKey {

        public static String KEY = "gtnhplanner.redo";
        public static int defVal = Keyboard.KEY_Z + NEIClientUtils.CTRL_HASH + NEIClientUtils.SHIFT_HASH;
    }

    public static class ConfigRedoAltKey {

        public static String KEY = "gtnhplanner.redo_alt";
        public static int defVal = Keyboard.KEY_Y + NEIClientUtils.CTRL_HASH;
    }

    private static final PlanOverlayHandler HANDLER = new PlanOverlayHandler();

    @Override
    public void loadConfig() {
        // First in line, so an item dropped from NEI's list reaches the board before ModularUI's own handler.
        codechicken.nei.api.GuiInfo.guiHandlers.addFirst(new FlowchartGuiHandler());
        API.addLayoutStyle(0, new FlowchartLayoutStyle());
        MinecraftForge.EVENT_BUS.register(this);
        // The plan button's menus: first for clicks and keys while one is open, drawn over everything NEI draws.
        GuiContainerManager.inputHandlers.addFirst(PlanMenu.INSTANCE);
        GuiContainerManager.addDrawHandler(PlanMenu.INSTANCE);
        GuiContainerManager.addObjectHandler(PlanMenu.INSTANCE);
        API.addOption(new OptionCycled(ConfigBurnableOverride.KEY, 2) {

            public boolean onClick(int button) {
                if (!super.onClick(button)) {
                    return false;
                } else {
                    Compat.init();
                    return true;
                }
            }
        });
        API.addHashBind(ConfigUndoKey.KEY, ConfigUndoKey.defVal);
        API.addHashBind(ConfigRedoKey.KEY, ConfigRedoKey.defVal);
        API.addHashBind(ConfigRedoAltKey.KEY, ConfigRedoAltKey.defVal);

        API.addOption(new OptionIntegerField(ConfigItemColumns.KEY, ConfigItemColumns.min, ConfigItemColumns.max));
    }

    @SubscribeEvent
    public void onPreButtonUpdate(final UpdateRecipeButtonsEvent.Pre event) {
        final GuiRecipe<?> gui = (GuiRecipe<?>) event.gui;
        if (!com.gtnhplanner.ui.Planner.isPlanner(gui.firstGui)) return;

        for (final Object h : gui.currenthandlers) {
            if (!(h instanceof final IRecipeHandler r)) continue;
            final String ident = r.getOverlayIdentifier();
            if (ident != null && !ident.isEmpty()
                && !RecipeInfo.hasOverlayHandler(GuiContainerWrapper.class, ident)) {
                API.registerGuiOverlayHandler(GuiContainerWrapper.class, HANDLER, ident);
            }
        }
    }

    @SubscribeEvent
    public void onPostButtonUpdate(final UpdateRecipeButtonsEvent.Post event) {
        final GuiRecipe<?> gui = (GuiRecipe<?>) event.gui;
        addPlanButton(event);
        if (!com.gtnhplanner.ui.Planner.isPlanner(gui.firstGui)) return;

        for (final GuiRecipeButton btn : event.buttonList) {
            if (btn instanceof GuiOverlayButton) {
                ((GuiOverlayButton) btn).setRequireShiftForOverlayRecipe(false);
            }
        }
    }

    /** The plan button, at the top of NEI's column of recipe buttons (+ at the bottom, the star above it). */
    private static void addPlanButton(final UpdateRecipeButtonsEvent.Post event) {
        final RecipeHandlerRef ref = event.recipeWidget.getRecipeHandlerRef();
        if (ref == null || !PlanOverlayHandler.plannable(ref.handler, ref.recipeIndex)) return;
        int x = Math.min(166, event.recipeWidget.w) - GuiRecipeButton.BUTTON_WIDTH;
        int top = event.recipeWidget.h - 5;
        for (final GuiRecipeButton b : event.buttonList) {
            x = b.xPosition;
            top = Math.min(top, b.yPosition);
        }
        event.buttonList.add(new PlanButton(ref, x, top - GuiRecipeButton.BUTTON_HEIGHT - 1));
    }

    @Override
    public String getName() {
        return GtnhPlanner.MODID;
    }

    @Override
    public String getVersion() {
        return Tags.VERSION;
    }
}

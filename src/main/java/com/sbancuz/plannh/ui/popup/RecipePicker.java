package com.sbancuz.plannh.ui.popup;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

import net.minecraft.item.ItemStack;

import org.jetbrains.annotations.Nullable;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.sbancuz.plannh.PlanNH;
import com.sbancuz.plannh.data.flowchart.Node;
import com.sbancuz.plannh.data.flowchart.Port;
import com.sbancuz.plannh.ui.theme.Hyb;

import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.GuiCraftingRecipe;
import codechicken.nei.recipe.GuiUsageRecipe;
import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.RecipeCatalysts;

/**
 * "What makes this?" / "What uses this?" inside the planner: every NEI recipe for an item, one row each (the machine,
 * the inputs, an arrow, the outputs, the recipe map's name), with a filter box. Clicking a row hands its handler and
 * recipe index back; the board adds it like NEI's "+". Rows read their ports from a preview node, built only for rows
 * on screen, so the picker shows exactly what the card will.
 */
public final class RecipePicker extends Widget<RecipePicker> implements Interactable {

    public static final int ROW = 22;
    private static final int VISIBLE = 9;
    private static final int WIDTH = 360;

    private static final class Row {

        final IRecipeHandler handler;
        final int index;
        final String mapName;
        ItemStack machine;
        List<Port<?>> inputs, outputs;
        String search;
        boolean failed;

        Row(final IRecipeHandler handler, final int index) {
            this.handler = handler;
            this.index = index;
            this.mapName = handler.getRecipeName()
                .trim();
        }

        void load() {
            if (inputs != null || failed) return;
            try {
                final Node preview = new Node(handler, index, 0, 0);
                inputs = preview.inputs;
                outputs = preview.outputs;
                final List<PositionedStack> cats = RecipeCatalysts.getRecipeCatalysts(handler);
                machine = cats.isEmpty() || cats.get(0) == null ? null : cats.get(0).item;
            } catch (final RuntimeException e) {
                failed = true;
                PlanNH.LOG.warn("Recipe preview failed for {} #{}", mapName, index, e);
            }
        }

        /** Map name plus every input and output name, lowercased; built from NEI's stacks so it stays cheap. */
        String search() {
            if (search != null) return search;
            final StringBuilder sb = new StringBuilder(mapName);
            try {
                for (final PositionedStack ps : handler.getIngredientStacks(index)) append(sb, ps);
                append(sb, handler.getResultStack(index));
                for (final PositionedStack ps : handler.getOtherStacks(index)) append(sb, ps);
            } catch (final RuntimeException ignored) {}
            search = sb.toString()
                .toLowerCase(Locale.ROOT);
            return search;
        }

        private static void append(final StringBuilder sb, @Nullable final PositionedStack ps) {
            if (ps == null || ps.item == null) return;
            try {
                sb.append(' ')
                    .append(ps.item.getDisplayName());
            } catch (final RuntimeException ignored) {}
        }
    }

    private final List<Row> all = new ArrayList<>();
    private final List<Row> shown = new ArrayList<>();
    private final BiConsumer<IRecipeHandler, Integer> pick;
    private String filter = "";
    private int scroll;
    private Popup popup;
    private TextFieldWidget field;

    private RecipePicker(final List<? extends IRecipeHandler> handlers,
        final BiConsumer<IRecipeHandler, Integer> pick) {
        this.pick = pick;
        for (final IRecipeHandler h : handlers) {
            if (isInfoPage(h)) continue;
            for (int i = 0; i < h.numRecipes(); i++) all.add(new Row(h, i));
        }
        shown.addAll(all);
        size(WIDTH - 8, Math.max(1, Math.min(VISIBLE, all.size())) * ROW);
    }

    /** NEI pages that describe the world rather than recipes (GregTech's ore vein tables). */
    private static boolean isInfoPage(final IRecipeHandler handler) {
        final String name = handler.getRecipeName()
            .toLowerCase(Locale.ROOT);
        return name.contains("vein stats") || name.contains("small ore");
    }

    /**
     * The picker for an item, or null when NEI knows no recipe for it. {@code uses} lists recipes that consume it
     * instead of ones that make it.
     */
    @Nullable
    public static Popup create(final ItemStack stack, final boolean uses,
        final BiConsumer<IRecipeHandler, Integer> pick) {
        final List<? extends IRecipeHandler> handlers = uses ? GuiUsageRecipe.getUsageHandlers("item", stack)
            : GuiCraftingRecipe.getCraftingHandlers("item", stack);
        final RecipePicker list = new RecipePicker(handlers, pick);
        if (list.all.isEmpty()) return null;
        final String title = (uses ? "WHAT USES " : "WHAT MAKES ") + stack.getDisplayName()
            .toUpperCase(Locale.ROOT) + "  (" + list.all.size() + ")";
        final Popup popup = new Popup("plannh_recipes", WIDTH, 4 + 14 + 18 + list.getArea().height + 4);
        list.popup = popup;
        popup.child(
            new TextWidget<>(IKey.str(Hyb.fit(title, WIDTH - 12))).color(Hyb.MUTED)
                .shadow(true)
                .pos(6, 6)
                .size(WIDTH - 12, 10));
        final TextFieldWidget field = new TextFieldWidget()
            .value(new StringValue.Dynamic(() -> list.filter, list::setFilter))
            .hintText("Filter by machine or item...")
            .pos(4, 18)
            .size(WIDTH - 8, 14);
        list.field = field;
        final boolean[] focused = { false };
        field.onUpdateListener(w -> {
            if (!focused[0] && w.isValid()) {
                focused[0] = true;
                w.getContext()
                    .focus(w);
            }
        }, true);
        popup.child(field);
        popup.child(list.pos(4, 36));
        return popup;
    }

    /** The field only commits on Enter, so read its live text every tick to filter as the player types. */
    @Override
    public void onUpdate() {
        super.onUpdate();
        if (field != null && !field.getText()
            .equals(filter)) setFilter(field.getText());
    }

    private void setFilter(final String text) {
        filter = text == null ? "" : text;
        final String f = filter.toLowerCase(Locale.ROOT)
            .trim();
        shown.clear();
        for (final Row r : all) if (f.isEmpty() || r.search()
            .contains(f)) shown.add(r);
        scroll = 0;
    }

    private int rowAtMouse() {
        final int y = getContext().getAbsMouseY() - getArea().y;
        if (y < 0 || y >= VISIBLE * ROW) return -1;
        final int i = scroll + y / ROW;
        return i < shown.size() ? i : -1;
    }

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        final float z = context.getCurrentDrawingZ();
        final int w = getArea().width;
        final int hover = isHovering() ? rowAtMouse() : -1;
        for (int k = 0; k < VISIBLE && scroll + k < shown.size(); k++) {
            final Row r = shown.get(scroll + k);
            r.load();
            final int y = k * ROW;
            if (scroll + k == hover) Hyb.rect(0, y, w, ROW, Hyb.MENU_HOVER);
            if (k > 0) Hyb.rect(2, y, w - 4, 1, 0x40000000);
            int x = 3;
            Hyb.item(r.machine, x, y + 3, 16, z);
            x += 20;
            if (r.failed) {
                Hyb.text("(can't read this recipe)", x, y + 7, Hyb.MUTED);
                continue;
            }
            x = icons(r.inputs, x, y + 3, 5, z);
            arrow(x + 2, y + 11);
            x += 11;
            x = icons(r.outputs, x, y + 3, 4, z);
            Hyb.textRight(Hyb.fit(r.mapName, w - x - 8), w - 4, y + 7, Hyb.MUTED);
        }
        if (shown.isEmpty()) Hyb.text("Nothing matches", 6, 6, Hyb.MUTED);
        if (shown.size() > VISIBLE) {
            final int track = VISIBLE * ROW;
            final int thumb = Math.max(10, track * VISIBLE / shown.size());
            final int top = (track - thumb) * scroll / Math.max(1, shown.size() - VISIBLE);
            Hyb.rect(w - 2, top, 2, thumb, Hyb.MUTED);
        }
    }

    /** A small right-pointing arrow (the font's arrow glyph renders tiny). */
    private static void arrow(final int x, final int y) {
        Hyb.rect(x, y - 1, 4, 2, Hyb.MUTED);
        Hyb.rect(x + 4, y - 3, 1, 6, Hyb.MUTED);
        Hyb.rect(x + 5, y - 2, 1, 4, Hyb.MUTED);
        Hyb.rect(x + 6, y - 1, 1, 2, Hyb.MUTED);
    }

    /** Up to {@code max} port icons from x; a "+n" when there are more. Returns the x after the last one. */
    private static int icons(final List<Port<?>> ports, int x, final int y, final int max, final float z) {
        for (int i = 0; i < ports.size() && i < max; i++) {
            final Port<?> p = ports.get(i);
            if (p.getValue() instanceof final net.minecraftforge.fluids.FluidStack fluid) Hyb.fluid(fluid, x, y, 16, z);
            else Hyb.item(p.getDisplayStack(), x, y, 16, z);
            x += 17;
        }
        if (ports.size() > max) {
            Hyb.text("+" + (ports.size() - max), x, y + 4, Hyb.MUTED);
            x += 12;
        }
        return x;
    }

    @Override
    public Result onMousePressed(final int mouseButton) {
        final int i = rowAtMouse();
        if (mouseButton != 0 || i < 0) return Result.IGNORE;
        final Row r = shown.get(i);
        if (popup != null) popup.closeIfOpen();
        pick.accept(r.handler, r.index);
        return Result.SUCCESS;
    }

    @Override
    public boolean onMouseScroll(final UpOrDown direction, final int amount) {
        final int max = Math.max(0, shown.size() - VISIBLE);
        scroll = Math.max(0, Math.min(max, scroll + (direction == UpOrDown.UP ? -1 : 1)));
        return true;
    }

    /** Tooltip for the row under the mouse: the recipe map and what clicking does. */
    public List<String> hoverLines() {
        if (!isHovering()) return null;
        final int i = rowAtMouse();
        if (i < 0) return null;
        final Row r = shown.get(i);
        final List<String> lines = new ArrayList<>();
        lines.add(r.mapName);
        if (r.inputs != null) {
            for (final Port<?> p : r.inputs) lines.add("§7in  " + p.getDisplayName());
            for (final Port<?> p : r.outputs) lines.add("§7out " + p.getDisplayName());
        }
        lines.add("§8Click: put it on the board");
        return lines;
    }
}

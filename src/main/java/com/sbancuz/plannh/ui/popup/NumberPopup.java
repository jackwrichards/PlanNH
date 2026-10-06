package com.sbancuz.plannh.ui.popup;

import java.util.function.DoubleConsumer;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.utils.MathUtils;
import com.cleanroommc.modularui.utils.ParseResult;
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.sbancuz.plannh.ui.theme.Hyb;

/**
 * A titled number box that takes focus on open with its text selected, so typing replaces it. Enter (or clicking
 * away) commits; ModularUI's maths parser reads it, so "2.5k" and "64/3" work. An empty box commits 0, which callers
 * read as "clear" (unpin) or clamp. Text that doesn't parse changes nothing.
 */
public final class NumberPopup {

    private NumberPopup() {}

    public static Popup create(final String title, final String hint, final double current, final double min,
        final double max, final DoubleConsumer commit) {
        final int width = Math.max(140, Hyb.width(title) + 16);
        final Popup popup = new Popup("plannh_number", width, 44);
        final String[] text = { format(current) };
        final boolean[] focused = { false };
        final TextFieldWidget field = new SelectAllField().value(new StringValue.Dynamic(() -> text[0], typed -> {
            text[0] = typed;
            final String t = typed.trim();
            if (t.isEmpty()) commit.accept(0);
            else {
                final ParseResult r = MathUtils.parseExpression(t, Double.NaN);
                if (r.isSuccess() && r.hasValue()) {
                    final double v = r.getResult()
                        .getNumberValue()
                        .doubleValue();
                    if (!Double.isNaN(v)) commit.accept(Math.max(min, Math.min(max, v)));
                }
            }
            popup.closeIfOpen();
        }))
            .hintText(hint)
            .pos(6, 20)
            .size(width - 12, 16);
        field.onUpdateListener(w -> {
            if (!focused[0] && w.isValid()) {
                focused[0] = true;
                w.getContext()
                    .focus(w);
            }
        }, true);
        popup.child(
            new TextWidget<>(IKey.str(title)).color(Hyb.MUTED)
                .shadow(true)
                .pos(6, 6)
                .size(width - 12, 10));
        popup.child(field);
        return popup;
    }

    /** Whole numbers without ".0"; zero or less as an empty box. */
    static String format(final double v) {
        if (v <= 0 || Double.isNaN(v)) return "";
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        return Double.toString(v);
    }

    private static final class SelectAllField extends TextFieldWidget {

        @Override
        public void onFocus(final ModularGuiContext context) {
            super.onFocus(context);
            handler.markAll();
        }
    }
}

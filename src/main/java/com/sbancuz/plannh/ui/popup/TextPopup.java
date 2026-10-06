package com.sbancuz.plannh.ui.popup;

import java.util.function.Consumer;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.sbancuz.plannh.ui.theme.Hyb;

/** A titled text box that takes focus with its text selected; Enter (or clicking away) commits. */
public final class TextPopup {

    private TextPopup() {}

    public static Popup create(final String title, final String current, final Consumer<String> commit) {
        final int width = Math.max(160, Hyb.width(title) + 16);
        final Popup popup = new Popup("plannh_text", width, 44);
        final String[] text = { current };
        final boolean[] focused = { false };
        final TextFieldWidget field = new SelectAllField().value(new StringValue.Dynamic(() -> text[0], typed -> {
            text[0] = typed;
            commit.accept(typed);
            popup.closeIfOpen();
        }))
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

    private static final class SelectAllField extends TextFieldWidget {

        @Override
        public void onFocus(final ModularGuiContext context) {
            super.onFocus(context);
            handler.markAll();
        }
    }
}

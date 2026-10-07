package com.gtnhplanner.ui.library;

import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.input.Keyboard;

import com.cleanroommc.modularui.api.widget.IFocusedWidget;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * A password box: what is typed shows as dots. ModularUI's text field has no such mode. Typing adds to the end,
 * Backspace takes off the last (Ctrl: all of it), Ctrl+V pastes, Enter runs {@link #onEnter}.
 */
final class PasswordField extends Widget<PasswordField> implements Interactable, IFocusedWidget {

    private static final int MAX = 128;

    private final StringBuilder text = new StringBuilder();
    private boolean focused;
    private Runnable onEnter = () -> {};
    private final String hint;

    PasswordField(final String hint) {
        this.hint = hint;
    }

    PasswordField onEnter(final Runnable r) {
        onEnter = r;
        return this;
    }

    String text() {
        return text.toString();
    }

    void clear() {
        text.setLength(0);
    }

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        final int w = getArea().width, h = getArea().height;
        Hyb.rect(0, 0, w, h, focused ? 0xFFE0E0E0 : 0xFF6A6C74);
        Hyb.rect(1, 1, w - 2, h - 2, 0xFF000000);
        int x = 4;
        final int cy = h / 2 - 1;
        if (text.length() == 0 && !focused) Hyb.text(hint, 4, (h - 8) / 2f, 0xFF6F737C);
        for (int i = 0; i < text.length() && x + 3 < w - 4; i++) {
            Hyb.rect(x, cy, 3, 3, 0xFFE0E0E0);
            x += 5;
        }
        if (focused && System.currentTimeMillis() / 500 % 2 == 0) Hyb.rect(x, 3, 1, h - 6, 0xFFE0E0E0);
    }

    @Override
    public Result onMousePressed(final int mouseButton) {
        getContext().focus(this);
        return Result.SUCCESS;
    }

    @Override
    public Result onKeyPressed(final char typedChar, final int keyCode) {
        if (!focused) return Result.IGNORE;
        switch (keyCode) {
            case Keyboard.KEY_BACK -> {
                if (GuiScreen.isCtrlKeyDown()) text.setLength(0);
                else if (text.length() > 0) text.setLength(text.length() - 1);
                return Result.SUCCESS;
            }
            case Keyboard.KEY_RETURN, Keyboard.KEY_NUMPADENTER -> {
                onEnter.run();
                return Result.SUCCESS;
            }
            case Keyboard.KEY_ESCAPE, Keyboard.KEY_TAB -> {
                return Result.IGNORE;
            }
            default -> {}
        }
        if (GuiScreen.isCtrlKeyDown() && keyCode == Keyboard.KEY_V) {
            final String paste = GuiScreen.getClipboardString();
            if (paste != null) for (final char c : paste.toCharArray()) add(c);
            return Result.SUCCESS;
        }
        if (typedChar >= ' ' && typedChar != 127) {
            add(typedChar);
            return Result.SUCCESS;
        }
        return Result.IGNORE;
    }

    private void add(final char c) {
        if (c >= ' ' && c != 127 && text.length() < MAX) text.append(c);
    }

    @Override
    public boolean isFocused() {
        return focused;
    }

    @Override
    public void onFocus(final ModularGuiContext context) {
        focused = true;
    }

    @Override
    public void onRemoveFocus(final ModularGuiContext context) {
        focused = false;
    }
}

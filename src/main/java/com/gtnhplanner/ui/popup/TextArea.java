package com.gtnhplanner.ui.popup;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ChatAllowedCharacters;

import org.lwjgl.input.Keyboard;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.widget.IFocusedWidget;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.gtnhplanner.ui.note.NoteText;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * Text of many lines to write in, in the planner's look: wrapped at word breaks (the sticky notes' layout), a caret
 * and a selection, the keys the notes take (arrows, Home and End, Ctrl with a word or the clipboard), a hint while it
 * is empty, and the wheel when it runs longer than the box.
 */
public final class TextArea extends Widget<TextArea> implements Interactable, IFocusedWidget {

    private static final int PAD = 4, LINE = 10;
    private static final int SELECTION = 0x6622D3EE;

    private final String hint;
    private String text = "";
    private int caret, anchor, scroll;
    private boolean focused, enabled = true;
    private long caretSince;
    /** The last key event taken, so one offered twice is typed once. */
    private long lastKeyNanos = -1;

    public TextArea(final String hint) {
        this.hint = hint;
    }

    public String text() {
        return text;
    }

    /** Stops taking input, as a form being sent does. */
    public void lock() {
        enabled = false;
        focused = false;
    }

    /** Takes input again (the send failed). */
    public void unlock() {
        enabled = true;
    }

    private NoteText layout() {
        return NoteText.of(text, getArea().width - 2 * PAD - 2, Hyb::width);
    }

    private int rows() {
        return Math.max(1, (getArea().height - 2 * PAD) / LINE);
    }

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        if (text.isEmpty() && !focused) {
            Hyb.text(hint, PAD, PAD + 1, 0xFF6A6C74);
            return;
        }
        final NoteText lay = layout();
        final int lines = lay.lines()
            .size();
        // The caret's line kept in view.
        final int at = lay.lineOf(caret);
        if (focused) {
            if (at < scroll) scroll = at;
            if (at >= scroll + rows()) scroll = at - rows() + 1;
        }
        scroll = Math.max(0, Math.min(scroll, lines - rows()));
        final int a = Math.min(caret, anchor), b = Math.max(caret, anchor);
        for (int i = scroll; i < Math.min(lines, scroll + rows()); i++) {
            final int y = PAD + (i - scroll) * LINE;
            final NoteText.Line l = lay.lines()
                .get(i);
            if (b > a && b > l.start() && a < l.end()) {
                final int from = Math.max(a, l.start()), to = Math.min(b, l.end());
                final int x0 = Hyb.width(text.substring(l.start(), from)),
                    x1 = Hyb.width(text.substring(l.start(), to));
                Hyb.rect(PAD + x0, y - 1, Math.max(2, x1 - x0), LINE, SELECTION);
            }
            Hyb.text(lay.shown(i), PAD, y, Hyb.INK);
        }
        if (focused && (System.currentTimeMillis() - caretSince) % 1000 < 600 && at >= scroll && at < scroll + rows())
            Hyb.rect(PAD + lay.xOf(caret), PAD + (at - scroll) * LINE - 1, 1, LINE, Hyb.INK);
        if (lines > rows()) {
            final int track = getArea().height - 4, bar = Math.max(8, track * rows() / lines);
            Hyb.rect(getArea().width - 3, 2 + (track - bar) * scroll / Math.max(1, lines - rows()), 2, bar, Hyb.MUTED);
        }
    }

    @Override
    public boolean canHover() {
        return true;
    }

    @Override
    public Result onMousePressed(final int mouseButton) {
        if (mouseButton != 0 || !enabled) return Result.ACCEPT;
        getContext().focus(this);
        final NoteText lay = layout();
        final int line = scroll + (getContext().getAbsMouseY() - getArea().y - PAD) / LINE;
        caret = lay.caretAt(line, getContext().getAbsMouseX() - getArea().x - PAD);
        if (!GuiScreen.isShiftKeyDown()) anchor = caret;
        caretSince = System.currentTimeMillis();
        return Result.SUCCESS;
    }

    @Override
    public boolean onMouseScroll(final UpOrDown direction, final int amount) {
        scroll += direction == UpOrDown.UP ? -1 : 1;
        return true;
    }

    @Override
    public boolean isFocused() {
        return focused;
    }

    @Override
    public void onFocus(final ModularGuiContext context) {
        focused = enabled;
        caretSince = System.currentTimeMillis();
    }

    @Override
    public void onRemoveFocus(final ModularGuiContext context) {
        focused = false;
    }

    @Override
    public Result onKeyPressed(final char typedChar, final int keyCode) {
        if (!focused) return Result.IGNORE;
        final long nanos = Keyboard.getEventNanoseconds();
        if (nanos == lastKeyNanos && nanos != 0) return Result.SUCCESS;
        lastKeyNanos = nanos;
        caretSince = System.currentTimeMillis();
        final boolean ctrl = GuiScreen.isCtrlKeyDown(), shift = GuiScreen.isShiftKeyDown();
        switch (keyCode) {
            case Keyboard.KEY_ESCAPE -> {
                getContext().removeFocus();
                return Result.SUCCESS;
            }
            case Keyboard.KEY_RETURN, Keyboard.KEY_NUMPADENTER -> {
                insert("\n");
                return Result.SUCCESS;
            }
            case Keyboard.KEY_BACK -> {
                if (caret == anchor) anchor = ctrl ? wordStart(caret) : Math.max(0, caret - 1);
                insert("");
                return Result.SUCCESS;
            }
            case Keyboard.KEY_DELETE -> {
                if (caret == anchor) anchor = ctrl ? wordEnd(caret) : Math.min(text.length(), caret + 1);
                insert("");
                return Result.SUCCESS;
            }
            case Keyboard.KEY_LEFT -> {
                move(caret != anchor && !shift ? Math.min(caret, anchor) : ctrl ? wordStart(caret) : caret - 1, shift);
                return Result.SUCCESS;
            }
            case Keyboard.KEY_RIGHT -> {
                move(caret != anchor && !shift ? Math.max(caret, anchor) : ctrl ? wordEnd(caret) : caret + 1, shift);
                return Result.SUCCESS;
            }
            case Keyboard.KEY_UP, Keyboard.KEY_DOWN -> {
                final NoteText lay = layout();
                final int line = lay.lineOf(caret) + (keyCode == Keyboard.KEY_UP ? -1 : 1);
                if (line < 0) move(0, shift);
                else if (line >= lay.lines()
                    .size()) move(text.length(), shift);
                else move(lay.caretAt(line, lay.xOf(caret)), shift);
                return Result.SUCCESS;
            }
            case Keyboard.KEY_HOME, Keyboard.KEY_END -> {
                final NoteText lay = layout();
                final NoteText.Line l = lay.lines()
                    .get(lay.lineOf(caret));
                move(keyCode == Keyboard.KEY_HOME ? l.start() : l.end(), shift);
                return Result.SUCCESS;
            }
            default -> {}
        }
        if (ctrl) {
            switch (keyCode) {
                case Keyboard.KEY_A -> {
                    anchor = 0;
                    caret = text.length();
                }
                case Keyboard.KEY_C -> {
                    if (caret != anchor) GuiScreen.setClipboardString(selected());
                }
                case Keyboard.KEY_X -> {
                    if (caret != anchor) {
                        GuiScreen.setClipboardString(selected());
                        insert("");
                    }
                }
                case Keyboard.KEY_V -> insert(
                    GuiScreen.getClipboardString()
                        .replace("\r", ""));
                default -> {}
            }
            return Result.SUCCESS;
        }
        if (typedChar != 0 && ChatAllowedCharacters.isAllowedCharacter(typedChar)) insert(String.valueOf(typedChar));
        // Every key stays here while writing (the planner's own keys must not act on it).
        return Result.SUCCESS;
    }

    private void insert(final String s) {
        final int a = Math.min(caret, anchor), b = Math.max(caret, anchor);
        text = text.substring(0, a) + s + text.substring(b);
        caret = anchor = a + s.length();
    }

    private String selected() {
        return text.substring(Math.min(caret, anchor), Math.max(caret, anchor));
    }

    private void move(final int to, final boolean extend) {
        caret = Math.max(0, Math.min(text.length(), to));
        if (!extend) anchor = caret;
    }

    private int wordStart(final int from) {
        int i = Math.max(0, from - 1);
        while (i > 0 && Character.isWhitespace(text.charAt(i))) i--;
        while (i > 0 && !Character.isWhitespace(text.charAt(i - 1))) i--;
        return i;
    }

    private int wordEnd(final int from) {
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        while (i < text.length() && !Character.isWhitespace(text.charAt(i))) i++;
        return i;
    }
}

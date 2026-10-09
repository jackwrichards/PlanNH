package com.gtnhplanner.ui.note;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ChatAllowedCharacters;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.input.Keyboard;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.widget.IDraggable;
import com.cleanroommc.modularui.api.widget.IFocusedWidget;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widget.sizer.Area;
import com.gtnhplanner.data.flowchart.Note;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.canvas.BoardCanvas;
import com.gtnhplanner.ui.popup.PickList;
import com.gtnhplanner.ui.popup.Popup;
import com.gtnhplanner.ui.popup.Tip;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * A sticky note on the board: coloured paper with its corner folded, the text wrapped on it. Drag it to move it (with
 * the selection), drag its folded corner or its right or bottom edge to resize it, double-click it to write on it.
 * Hovering shows its keys: delete at its top left, smaller and bigger text at its top right; a right-click offers
 * editing, the colour, the text size and delete. Notes take no part in the solve or the wiring: they sit over the wires
 * and under the cards.
 */
public final class NoteCard extends Widget<NoteCard> implements Interactable, IDraggable, IFocusedWidget {

    public enum Part {
        BODY,
        DELETE,
        SMALLER,
        BIGGER,
        RESIZE
    }

    /** The folded corner, at most; it shrinks on a small note. */
    private static final int FOLD = 14;
    /** How deep the edges that resize reach in. */
    private static final int EDGE = 5;
    /**
     * The keys (delete in the glued strip's left end, text size in its right end): their side, the gap between the
     * two on the right and their inset from the edge, at the board's own scale. Zoomed out they keep that size on
     * screen, so they can always be hit; they show whenever they fit on the note.
     */
    private static final int KEY = 9, KEY_GAP = 2, KEY_INSET = 3;
    /** The glued strip along the top, where the keys sit. */
    private static final int BAND = 11;
    /** The text's margin from the edges, and its first line's top. */
    private static final int PAD = 7, TEXT_TOP = BAND + 4;
    /** A line's height at the board's own text size (the font is 9 high; 14 px on the website). */
    private static final int LINE = 11;

    private final BoardSession session;
    public final UUID noteId;
    private boolean moving;

    public NoteCard(final BoardSession session, final UUID noteId) {
        this.session = session;
        this.noteId = noteId;
        final Note n = note();
        if (n != null) {
            pos(n.getX(), n.getY());
            size(n.getWidth(), n.getHeight());
        }
    }

    /** The note, looked up afresh: an undo puts a new graph in place. */
    @Nullable
    public Note note() {
        return session.graph().notes.get(noteId);
    }

    private BoardCanvas canvas() {
        return getParent() instanceof final BoardCanvas c ? c : null;
    }

    /** The place and size last given to the widget, so it is only told again when the note changes. */
    private int shownX = Integer.MIN_VALUE, shownY, shownW, shownH;

    @Override
    public void onUpdate() {
        super.onUpdate();
        sync();
    }

    /** Puts the widget where the note is, at its size (or the size it is being dragged or written to). */
    public void sync() {
        final Note n = note();
        if (n == null) return;
        final int w = resizing ? liveW : editing ? Math.max(n.getWidth(), editW) : n.getWidth();
        final int h = resizing ? liveH : editing ? Math.max(n.getHeight(), editH) : n.getHeight();
        if (shownX == n.getX() && shownY == n.getY() && shownW == w && shownH == h) return;
        shownX = n.getX();
        shownY = n.getY();
        shownW = w;
        shownH = h;
        pos(n.getX(), n.getY());
        size(w, h);
        scheduleResize();
    }

    @Override
    public boolean canHover() {
        return true;
    }

    // region Geometry

    private int w() {
        return getArea().width;
    }

    private int h() {
        return getArea().height;
    }

    private int fold() {
        return Math.max(6, Math.min(FOLD, Math.min(w(), h()) / 4));
    }

    private float localX() {
        final BoardCanvas c = canvas();
        final Note n = note();
        return c == null || n == null ? -1 : c.worldX(getContext().getAbsMouseX()) - n.getX();
    }

    private float localY() {
        final BoardCanvas c = canvas();
        final Note n = note();
        return c == null || n == null ? -1 : c.worldY(getContext().getAbsMouseY()) - n.getY();
    }

    public Part partAt(final float x, final float y) {
        if (editing) return Part.BODY;
        final int w = w(), h = h(), f = fold();
        // The folded corner and the right and bottom edges resize.
        if (x >= w - f && y >= h - f && x - (w - f) + (y - (h - f)) >= f - 3) return Part.RESIZE;
        if (x >= w - EDGE || y >= h - EDGE) return Part.RESIZE;
        if (keysShown()) for (final Part key : KEYS) {
            final float[] r = keyRect(key);
            if (x >= r[0] && y >= r[1] && x < r[0] + r[2] && y < r[1] + r[3]) return key;
        }
        return Part.BODY;
    }

    private static final Part[] KEYS = { Part.DELETE, Part.SMALLER, Part.BIGGER };

    /** How many note units a screen pixel of a key takes: 1 at the board's own scale and closer, more zoomed out. */
    private float keyUnit() {
        return Math.max(
            1f,
            1f / session.graph()
                .getZoom());
    }

    /** A key's note-local rectangle {x, y, w, h}. */
    private float[] keyRect(final Part key) {
        final float u = keyUnit(), k = KEY * u, inset = KEY_INSET * u;
        return switch (key) {
            case DELETE -> new float[] { inset, u, k, k };
            case SMALLER -> new float[] { w() - 2 * k - KEY_GAP * u - inset, u, k, k };
            case BIGGER -> new float[] { w() - k - inset, u, k, k };
            default -> new float[] { 0, 0, 0, 0 };
        };
    }

    /** Whether the three keys fit on the note at their size, with room between the left one and the right two. */
    private boolean keysFit() {
        final float u = keyUnit();
        return w() >= (3 * KEY + 2 * KEY_GAP + 2 * KEY_INSET) * u && h() >= (2 * KEY + 1) * u;
    }

    /** Note-local rectangle of a part, for the dev harness and the tour. */
    public int[] partRect(final Part part) {
        final int w = w(), h = h(), f = fold();
        return switch (part) {
            case BODY -> new int[] { 0, 0, w, h };
            case DELETE, SMALLER, BIGGER -> {
                final float[] r = keyRect(part);
                yield new int[] { Math.round(r[0]), Math.round(r[1]), Math.round(r[2]), Math.round(r[3]) };
            }
            case RESIZE -> new int[] { w - f, h - f, f, f };
        };
    }

    public Part partUnderMouse() {
        final float x = localX(), y = localY();
        return x < 0 || y < 0 || x >= w() || y >= h() ? null : partAt(x, y);
    }

    /** The keys show while the mouse is on the note, at any zoom, when they fit on it. */
    private boolean keysShown() {
        return isHovering() && !editing && keysFit();
    }

    // endregion

    // region Text layout

    private float scale(final Note n) {
        return n.fontSizeOrDefault() / (float) Note.DEFAULT_FONT;
    }

    private NoteText layout;
    private String layoutText;
    private float layoutWidth;

    /** The text laid out for the note's width at its text size, kept until either changes. */
    private NoteText layout(final String text, final float width) {
        if (layout == null || !text.equals(layoutText) || width != layoutWidth) {
            layout = NoteText.of(text, width, Hyb::width);
            layoutText = text;
            layoutWidth = width;
        }
        return layout;
    }

    /** The width the text wraps at, in the font's own units. */
    private float wrapWidth(final Note n, final int w) {
        return Math.max(10, (w - 2 * PAD) / scale(n));
    }

    // endregion

    // region Drawing

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        final Note n = note();
        if (n == null) return;
        final String tag = n.colorTag();
        final int w = w(), h = h(), f = fold();
        final BoardCanvas board = canvas();
        final boolean carried = board != null && board.isCarried(noteId);
        final int paper = NoteColors.paper(tag), ink = NoteColors.ink(tag);

        Hyb.beginBatch();
        // A soft shadow, lifted further while carried.
        final int lift = carried ? 3 : 0;
        for (int i = 2; i >= 0; i--) {
            final float g = i * 1.5f;
            Hyb.rect(2 - g + lift, 3 - g + lift * 1.3f, w + 2 * g - f / 2f, h + 2 * g - f / 2f, 0x16000000);
        }
        if (session.isSelected(noteId) || editing) Hyb.ring(
            0,
            0,
            w,
            h,
            Math.max(
                1,
                1.5f / session.graph()
                    .getZoom()),
            Hyb.SELECTION);
        // The paper, its corner folded over: the flap darker, the corner past it gone.
        Hyb.rect(0, 0, w, h - f, paper);
        Hyb.rect(0, h - f, w - f, f, paper);
        Hyb.triangle(w - f, h - f, w, h - f, w - f, h, NoteColors.fold(tag));
        Hyb.rect(0, 0, w, BAND, NoteColors.band(tag));
        Hyb.rect(0, h - 1, w - f, 1, NoteColors.edge(tag));
        Hyb.rect(w - 1, 0, 1, h - f, NoteColors.edge(tag));
        if (isHovering() && !editing) {
            // The grip: three short strokes on the fold, along its edge.
            final int grip = Hyb.mix(ink, NoteColors.fold(tag), 0.4f);
            for (int a = 4; a < f; a += 3)
                for (int t = 1; t < a - 1; t++) Hyb.rect(w - f + a - t, h - f + t, 1, 1, grip);
        }
        Hyb.endBatch();

        final float s = scale(n);
        final String text = editing ? draft : n.joined();
        final NoteText lay = layout(text, wrapWidth(n, w));
        final float lineH = LINE * s;
        final float bottom = h - Math.max(4, f / 2f);
        if (editing && hasSelection()) {
            // The selection, line by line, under the text.
            final int a = Math.min(caret, anchor), b = Math.max(caret, anchor);
            for (int i = 0; i < lay.lines()
                .size(); i++) {
                final NoteText.Line l = lay.lines()
                    .get(i);
                final int from = Math.max(a, l.start()), to = Math.min(b, l.end());
                if (from > to || from == to && !(a <= l.start() && b > l.end())) continue;
                final float y = TEXT_TOP + i * lineH;
                if (y + 9 * s > bottom) break;
                final float x0 = PAD + Hyb.width(text.substring(l.start(), from)) * s;
                final float x1 = PAD + Hyb.width(text.substring(l.start(), to)) * s;
                Hyb.rect(x0, y - 1, Math.max(2, x1 - x0), lineH, 0x40000000 | ink & 0xFFFFFF);
            }
        }
        if (text.isEmpty() && !editing) {
            Hyb.text("Double-click to write", PAD, TEXT_TOP, s, 0x90000000 | ink & 0xFFFFFF, false);
        } else {
            for (int i = 0; i < lay.lines()
                .size(); i++) {
                final float y = TEXT_TOP + i * lineH;
                if (y + 9 * s > bottom) break;
                Hyb.text(lay.shown(i), PAD, y, s, ink, false);
            }
        }
        if (editing && (System.currentTimeMillis() - caretSince) % 1000 < 600) {
            final int line = lay.lineOf(caret);
            final float y = TEXT_TOP + line * lineH;
            if (y + 9 * s <= bottom) Hyb.rect(PAD + lay.xOf(caret) * s, y - 1, Math.max(1, s), 10 * s, ink);
        }
        if (keysShown()) {
            drawKey(Part.DELETE, true);
            drawKey(Part.SMALLER, n.fontSizeOrDefault() > Note.MIN_FONT);
            drawKey(Part.BIGGER, n.fontSizeOrDefault() < Note.MAX_FONT);
        }
    }

    /** A key: a small dark square with a cross, a minus or a plus, drawn in its own pixels however far out. */
    private void drawKey(final Part key, final boolean enabled) {
        final float[] r = keyRect(key);
        final float x = r[0], y = r[1], u = keyUnit();
        final boolean lit = partUnderMouse() == key && enabled;
        Hyb.rect(x, y, r[2], r[3], lit ? 0xE0303238 : 0xB0303238);
        final int c = enabled ? 0xFFF2F3F7 : 0x80F2F3F7;
        switch (key) {
            case DELETE -> {
                for (int i = 0; i < KEY - 4; i++) {
                    Hyb.rect(x + (2 + i) * u, y + (2 + i) * u, u, u, c);
                    Hyb.rect(x + (KEY - 3 - i) * u, y + (2 + i) * u, u, u, c);
                }
            }
            case SMALLER -> Hyb.rect(x + 2 * u, y + 4 * u, (KEY - 4) * u, u, c);
            default -> {
                Hyb.rect(x + 2 * u, y + 4 * u, (KEY - 4) * u, u, c);
                Hyb.rect(x + 4 * u, y + 2 * u, u, (KEY - 4) * u, c);
            }
        }
    }

    /** The tip for the part under the mouse; the paper itself only explains itself after a moment. */
    @Nullable
    public Tip tip(final long hoveredFor) {
        if (editing) return null;
        final Part part = partUnderMouse();
        if (part == null) return null;
        return switch (part) {
            case DELETE -> Tip.of("Delete note");
            case SMALLER -> Tip.of("Smaller text");
            case BIGGER -> Tip.of("Bigger text");
            case RESIZE -> Tip.of("Resize")
                .action(Tip.Input.DRAG, "Drag the corner or an edge");
            case BODY -> hoveredFor < 700 ? null
                : Tip.of("Sticky note")
                    .action(Tip.Input.LEFT, "Double-click: write")
                    .action(Tip.Input.DRAG, "Move")
                    .action(Tip.Input.RIGHT, "Colour, text size, delete");
        };
    }

    // endregion

    // region Mouse

    private long lastPress;

    @Override
    public Result onMousePressed(final int mouseButton) {
        final Note n = note();
        if (n == null) return Result.IGNORE;
        if (editing) {
            if (mouseButton == 0) placeCaret(GuiScreen.isShiftKeyDown());
            return Result.SUCCESS;
        }
        if (mouseButton == 1) {
            openMenu();
            return Result.SUCCESS;
        }
        if (mouseButton != 0) return Result.IGNORE;
        switch (partAt(localX(), localY())) {
            case DELETE -> {
                Hyb.click();
                session.deleteNote(noteId);
                return Result.SUCCESS;
            }
            case SMALLER -> {
                stepFont(-1);
                return Result.SUCCESS;
            }
            case BIGGER -> {
                stepFont(1);
                return Result.SUCCESS;
            }
            case RESIZE -> {
                return Result.ACCEPT;
            }
            default -> {
                final long now = System.currentTimeMillis();
                if (now - lastPress < 400) {
                    lastPress = 0;
                    startEditing();
                    return Result.SUCCESS;
                }
                lastPress = now;
                return Result.ACCEPT;
            }
        }
    }

    @Override
    public boolean onMouseScroll(final UpOrDown direction, final int amount) {
        return false;
    }

    /** Steps the text size by the website's step. */
    public void stepFont(final int steps) {
        final Note n = note();
        if (n == null) return;
        final int next = Math
            .max(Note.MIN_FONT, Math.min(Note.MAX_FONT, n.fontSizeOrDefault() + steps * Note.FONT_STEP));
        if (next == n.fontSizeOrDefault()) return;
        Hyb.click();
        session.editLayout(() -> n.setFontSize(next));
    }

    private void openMenu() {
        final Note n = note();
        if (n == null) return;
        final List<PickList.Entry> rows = new ArrayList<>();
        rows.add(PickList.Entry.of("Write on it", this::startEditing));
        rows.add(PickList.Entry.of("Colour...", this::openColours));
        rows.add(
            new PickList.Entry(null, "Bigger text", n.fontSizeOrDefault() + " px", Hyb.INK, false, () -> stepFont(1)));
        rows.add(PickList.Entry.of("Smaller text", () -> stepFont(-1)));
        rows.add(new PickList.Entry(null, "Delete note", "", Hyb.RED_INK, false, () -> session.deleteNote(noteId)));
        Popup.open(
            getPanel(),
            PickList.popup("gtnhplanner_note", null, rows, false, 140),
            getContext().getAbsMouseX(),
            getContext().getAbsMouseY());
    }

    private void openColours() {
        final Note n = note();
        if (n == null) return;
        final List<PickList.Entry> rows = new ArrayList<>();
        for (final String[] c : NoteColors.MENU) rows.add(
            new PickList.Entry(
                null,
                "■ " + c[1],
                "",
                NoteColors.paper(c[0]),
                c[0].equals(n.colorTag()),
                () -> session.editLayout(() -> n.setColor(c[0]))));
        Popup.open(
            getPanel(),
            PickList.popup("gtnhplanner_note_colour", "Colour", rows, false, 120, NoteColors.MENU.size()),
            getContext().getAbsMouseX(),
            getContext().getAbsMouseY());
    }

    // endregion

    // region Moving and resizing

    private boolean resizing, resizeW, resizeH;
    private int liveW, liveH, startW, startH, startMouseX, startMouseY;
    private String resizeUndo;

    @Override
    public boolean onDragStart(final int button) {
        final Note n = note();
        if (button != 0 || n == null || editing || canvas() == null) return false;
        final float x = localX(), y = localY();
        if (partAt(x, y) == Part.RESIZE) {
            resizing = true;
            final int f = fold();
            final boolean corner = x >= w() - f && y >= h() - f;
            resizeW = corner || x >= w() - EDGE;
            resizeH = corner || y >= h() - EDGE;
            startW = liveW = n.getWidth();
            startH = liveH = n.getHeight();
            startMouseX = getContext().getAbsMouseX();
            startMouseY = getContext().getAbsMouseY();
            resizeUndo = com.gtnhplanner.api.PlanAPI.undoHistory(session.graph())
                .beginEdit(session.graph());
            return true;
        }
        if (partAt(x, y) != Part.BODY) return false;
        canvas().beginMove(noteId);
        return true;
    }

    @Override
    public void onDrag(final int mouseButton, final long timeSinceLastClick) {
        if (resizing) {
            final float zoom = session.graph()
                .getZoom();
            if (resizeW) liveW = Math
                .max(Note.MIN_W, BoardSession.snap(startW + (getContext().getAbsMouseX() - startMouseX) / zoom));
            if (resizeH) liveH = Math
                .max(Note.MIN_H, BoardSession.snap(startH + (getContext().getAbsMouseY() - startMouseY) / zoom));
            sync();
            return;
        }
        if (canvas() != null) canvas().dragMove();
    }

    @Override
    public void onDragEnd(final boolean successful) {
        if (resizing) {
            resizing = false;
            final Note n = note();
            if (n != null && (liveW != startW || liveH != startH)) {
                n.setWidth(liveW);
                n.setHeight(liveH);
                com.gtnhplanner.api.PlanAPI.undoHistory(session.graph())
                    .commitEdit(resizeUndo, session.graph());
                session.graph()
                    .touchLayout();
                com.gtnhplanner.api.PlanAPI.save();
            }
            resizeUndo = null;
            return;
        }
        if (canvas() != null) canvas().endMove(successful);
    }

    @Override
    public void drawMovingState(final ModularGuiContext context, final float partialTicks) {}

    @Override
    public @Nullable Area getMovingArea() {
        return null;
    }

    @Override
    public boolean isMoving() {
        return moving;
    }

    @Override
    public void setMoving(final boolean moving) {
        this.moving = moving;
    }

    // endregion

    // region Writing

    private boolean editing;
    /** The text being written, the caret in it and the other end of the selection (equal: nothing selected). */
    private String draft = "";
    private int caret, anchor;
    private long caretSince;
    /** The last key event taken, so one offered twice is typed once. */
    private long lastKeyNanos = -1;

    public boolean editing() {
        return editing;
    }

    /** Starts writing on the note, everything selected so typing replaces it (as the website does). */
    public void startEditing() {
        final Note n = note();
        if (n == null) return;
        draft = n.joined();
        anchor = 0;
        caret = draft.length();
        caretSince = System.currentTimeMillis();
        editW = n.getWidth();
        editH = n.getHeight();
        editing = true;
        session.select(noteId, false);
        getContext().focus(this);
    }

    /** Ends writing: the text goes in as one undoable step, the note grown to show it all. */
    public void stopEditing() {
        if (!editing) return;
        editing = false;
        final Note n = note();
        if (n == null) return;
        final int needed = neededHeight(n, draft);
        if (!draft.equals(n.joined()) || needed > n.getHeight()) {
            final String text = draft;
            session.editLayout(() -> {
                n.setJoined(text);
                n.setHeight(Math.max(n.getHeight(), needed));
            });
        }
    }

    /** The height that shows every line of {@code text} at the note's width and text size. */
    private int neededHeight(final Note n, final String text) {
        final NoteText lay = NoteText.of(text, wrapWidth(n, n.getWidth()), Hyb::width);
        final float lines = lay.lines()
            .size();
        final int f = Math.max(6, Math.min(FOLD, Math.min(n.getWidth(), n.getHeight()) / 4));
        return (int) Math.ceil((TEXT_TOP + lines * LINE * scale(n) + Math.max(4, f / 2f) + 3) / 10f) * 10;
    }

    @Override
    public boolean isFocused() {
        return editing;
    }

    @Override
    public void onFocus(final ModularGuiContext context) {}

    @Override
    public void onRemoveFocus(final ModularGuiContext context) {
        stopEditing();
    }

    private boolean hasSelection() {
        return caret != anchor;
    }

    private void placeCaret(final boolean extend) {
        final Note n = note();
        if (n == null) return;
        final float s = scale(n);
        final NoteText lay = layout(draft, wrapWidth(n, w()));
        final int line = (int) Math.floor((localY() - TEXT_TOP + 1) / (LINE * s));
        caret = lay.caretAt(line, (localX() - PAD) / s);
        if (!extend) anchor = caret;
        caretSince = System.currentTimeMillis();
    }

    @Override
    public Result onKeyPressed(final char typedChar, final int keyCode) {
        if (!editing) return Result.IGNORE;
        final long nanos = Keyboard.getEventNanoseconds();
        if (nanos == lastKeyNanos && nanos != 0 && !com.gtnhplanner.ui.tutorial.Pointer.typing) return Result.SUCCESS;
        lastKeyNanos = nanos;
        caretSince = System.currentTimeMillis();
        final boolean ctrl = GuiScreen.isCtrlKeyDown(), shift = GuiScreen.isShiftKeyDown();
        switch (keyCode) {
            case Keyboard.KEY_ESCAPE -> {
                getContext().removeFocus();
                stopEditing();
                return Result.SUCCESS;
            }
            case Keyboard.KEY_RETURN, Keyboard.KEY_NUMPADENTER -> {
                insert("\n");
                return Result.SUCCESS;
            }
            case Keyboard.KEY_BACK -> {
                if (!hasSelection()) anchor = ctrl ? wordStart(caret) : Math.max(0, caret - 1);
                insert("");
                return Result.SUCCESS;
            }
            case Keyboard.KEY_DELETE -> {
                if (!hasSelection()) anchor = ctrl ? wordEnd(caret) : Math.min(draft.length(), caret + 1);
                insert("");
                return Result.SUCCESS;
            }
            case Keyboard.KEY_LEFT -> {
                moveCaret(
                    hasSelection() && !shift ? Math.min(caret, anchor) : ctrl ? wordStart(caret) : caret - 1,
                    shift);
                return Result.SUCCESS;
            }
            case Keyboard.KEY_RIGHT -> {
                moveCaret(
                    hasSelection() && !shift ? Math.max(caret, anchor) : ctrl ? wordEnd(caret) : caret + 1,
                    shift);
                return Result.SUCCESS;
            }
            case Keyboard.KEY_UP, Keyboard.KEY_DOWN -> {
                final Note n = note();
                if (n != null) {
                    final NoteText lay = layout(draft, wrapWidth(n, w()));
                    final int line = lay.lineOf(caret) + (keyCode == Keyboard.KEY_UP ? -1 : 1);
                    if (line < 0) moveCaret(0, shift);
                    else if (line >= lay.lines()
                        .size()) moveCaret(draft.length(), shift);
                    else moveCaret(lay.caretAt(line, lay.xOf(caret)), shift);
                }
                return Result.SUCCESS;
            }
            case Keyboard.KEY_HOME, Keyboard.KEY_END -> {
                final Note n = note();
                if (n != null) {
                    final NoteText lay = layout(draft, wrapWidth(n, w()));
                    final NoteText.Line l = lay.lines()
                        .get(lay.lineOf(caret));
                    moveCaret(keyCode == Keyboard.KEY_HOME ? l.start() : l.end(), shift);
                }
                return Result.SUCCESS;
            }
            default -> {}
        }
        if (ctrl) {
            switch (keyCode) {
                case Keyboard.KEY_A -> {
                    anchor = 0;
                    caret = draft.length();
                }
                case Keyboard.KEY_C -> {
                    if (hasSelection()) GuiScreen.setClipboardString(selected());
                }
                case Keyboard.KEY_X -> {
                    if (hasSelection()) {
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
        // Every key stays with the note while it is being written on (Delete must not delete the selection).
        return Result.SUCCESS;
    }

    /** Types {@code s} at the caret, over the selection; the note grows to show the line the caret is on. */
    public void insert(final String s) {
        final int a = Math.min(caret, anchor), b = Math.max(caret, anchor);
        draft = draft.substring(0, a) + s + draft.substring(b);
        caret = anchor = a + s.length();
        growToFit();
    }

    /** Selects everything typed so far: the next typing replaces it. */
    public void selectAll() {
        anchor = 0;
        caret = draft.length();
    }

    /** The size the note shows at while it is written on: it grows with the text, and is saved when writing ends. */
    private int editW, editH;

    private void growToFit() {
        final Note n = note();
        if (n == null) return;
        editH = Math.max(n.getHeight(), neededHeight(n, draft));
    }

    private String selected() {
        return draft.substring(Math.min(caret, anchor), Math.max(caret, anchor));
    }

    private void moveCaret(final int to, final boolean extend) {
        caret = Math.max(0, Math.min(draft.length(), to));
        if (!extend) anchor = caret;
    }

    private int wordStart(final int from) {
        int i = Math.max(0, from - 1);
        while (i > 0 && Character.isWhitespace(draft.charAt(i))) i--;
        while (i > 0 && !Character.isWhitespace(draft.charAt(i - 1))) i--;
        return i;
    }

    private int wordEnd(final int from) {
        int i = from;
        while (i < draft.length() && Character.isWhitespace(draft.charAt(i))) i++;
        while (i < draft.length() && !Character.isWhitespace(draft.charAt(i))) i++;
        return i;
    }

    // endregion
}

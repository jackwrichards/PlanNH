package com.gtnhplanner.ui.library;

import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;

import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.library.Account;
import com.gtnhplanner.library.Posting;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.popup.Popup;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * Signing in to gtnhplanner.com, and posting a plan to its public setups: two small forms in the board's popup style.
 * A title and a line of what it is about, the fields, the reason in amber when the site said no, one cyan button (Enter
 * presses it), and a link to the other way round (sign in or make an account).
 */
public final class AccountForms {

    private static final int W = 230, PAD = 10, FIELD_H = 16, CYAN = 0xFF22D3EE;

    private AccountForms() {}

    /** Signs in (or makes an account), then runs {@code then}. */
    public static void signIn(final ModularPanel parent, final Runnable then) {
        final Popup popup = new Popup("gtnhplanner_sign_in", W, 164);
        popup.child(new SignIn(popup, then).size(W, 164));
        open(parent, popup, 164);
    }

    /** Posts a plan with a title (and, if wanted, a line about it); signs in first when needed. */
    public static void post(final ModularPanel parent, final BoardSession session, final Graph plan) {
        if (!Account.signedIn()) {
            signIn(parent, () -> post(parent, session, plan));
            return;
        }
        final Popup popup = new Popup("gtnhplanner_post", W, 158);
        popup.child(new Post(popup, session, plan).size(W, 158));
        open(parent, popup, 158);
    }

    /** In the middle of the screen. */
    private static void open(final ModularPanel parent, final Popup popup, final int h) {
        final ScaledResolution sr = new ScaledResolution(
            Minecraft.getMinecraft(),
            Minecraft.getMinecraft().displayWidth,
            Minecraft.getMinecraft().displayHeight);
        Popup.open(parent, popup, (sr.getScaledWidth() - W) / 2, (sr.getScaledHeight() - h) / 2 - 20);
    }

    /** What both forms share: the title and subtitle, labels over fields, the error, the button and the link. */
    private abstract static class Form extends ParentWidget<Form> implements Interactable {

        final Popup popup;
        @Nullable
        String error;
        boolean busy;
        private int buttonY, linkY;

        Form(final Popup popup) {
            this.popup = popup;
        }

        abstract String title();

        abstract String subtitle();

        abstract String button();

        abstract String busyButton();

        /** The link under the button, or null. */
        @Nullable
        abstract String link();

        abstract void submit();

        void onLink() {}

        /** A muted line between the fields and the button, or null. */
        @Nullable
        String note() {
            return null;
        }

        /** A text field that submits the form on Enter (only: ModularUI\'s also commit when they lose focus). */
        TextFieldWidget field(final String hint, final String initial, final int y) {
            final String[] text = { initial };
            final TextFieldWidget f = new EnterField(this::submit, this::next)
                .value(new StringValue.Dynamic(() -> text[0], s -> text[0] = s == null ? "" : s))
                .hintText(hint)
                .pos(PAD, y)
                .size(W - 2 * PAD, FIELD_H);
            f.setText(initial);
            child(f);
            return f;
        }

        /** Tab: on to the next field. */
        void next() {}

        @Override
        public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
            Hyb.text(title(), PAD, PAD, Hyb.INK);
            Hyb.text(Hyb.fit(subtitle(), W - 2 * PAD), PAD, PAD + 11, Hyb.MUTED);
            labels();
            final int h = getArea().height;
            linkY = h - PAD - 9;
            buttonY = linkY - 24;
            int y = buttonY - 13;
            if (error != null) {
                Hyb.text(Hyb.fit(error, W - 2 * PAD), PAD, y, Hyb.AMBER_INK);
                y -= 11;
            }
            final String note = note();
            if (note != null && error == null) Hyb.text(Hyb.fit(note, W - 2 * PAD), PAD, y, 0xFF7A7C84);
            final boolean hot = !busy && over(PAD, buttonY, W - 2 * PAD, 18);
            Hyb.rect(PAD, buttonY, W - 2 * PAD, 18, busy ? 0xFF1E6B78 : hot ? 0xFF38E1F5 : CYAN);
            Hyb.rect(PAD, buttonY, W - 2 * PAD, 1, 0x66FFFFFF);
            final String label = busy ? busyButton() : button();
            com.cleanroommc.modularui.drawable.GuiDraw
                .drawText(label, (W - Hyb.width(label)) / 2f, buttonY + 5, 1f, 0xFF0B1A1E, false);
            final String link = link();
            if (link != null) {
                final boolean linkHot = over((W - Hyb.width(link)) / 2, linkY, Hyb.width(link), 9);
                Hyb.textCentered(link, W / 2f, linkY, linkHot ? 0xFFFFFFFF : 0xFF9FD9E6);
            }
        }

        /** The labels over the fields. */
        abstract void labels();

        private boolean over(final int x, final int y, final int w, final int h) {
            final int mx = getContext().getAbsMouseX() - getArea().x, my = getContext().getAbsMouseY() - getArea().y;
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }

        @Override
        public Result onMousePressed(final int mouseButton) {
            if (mouseButton != 0) return Result.ACCEPT;
            if (!busy && over(PAD, buttonY, W - 2 * PAD, 18)) {
                Hyb.click();
                submit();
                return Result.SUCCESS;
            }
            final String link = link();
            if (link != null && over((W - Hyb.width(link)) / 2, linkY, Hyb.width(link), 9)) {
                Hyb.click();
                onLink();
                return Result.SUCCESS;
            }
            return Result.ACCEPT;
        }

        @Override
        public boolean canHover() {
            return true;
        }
    }

    /** Signing in with the website's username and password, or making an account there. */
    private static final class SignIn extends Form {

        private final Runnable then;
        private final TextFieldWidget username;
        private final PasswordField password;
        private boolean create;
        private boolean focusedOnce;

        SignIn(final Popup popup, final Runnable then) {
            super(popup);
            this.then = then;
            final String last = Account.username();
            username = field("Username", last == null ? "" : last, 44);
            password = new PasswordField("Password").onEnter(this::submit);
            password.pos(PAD, 78)
                .size(W - 2 * PAD, FIELD_H);
            child(password);
        }

        @Override
        public void onUpdate() {
            super.onUpdate();
            // Straight into the first empty field.
            if (!focusedOnce && isValid()) {
                focusedOnce = true;
                if (username.getText()
                    .isEmpty()) getContext().focus(username);
                else getContext().focus(password);
            }
        }

        @Override
        void next() {
            getContext().focus(password);
        }

        @Override
        String title() {
            return create ? "Create a gtnhplanner.com account" : "Sign in to gtnhplanner.com";
        }

        @Override
        String subtitle() {
            return create ? "To post your plans to the public library" : "The same account as on the website";
        }

        @Override
        void labels() {
            Hyb.text("USERNAME", PAD, 34, 0xFF7A7C84);
            Hyb.text("PASSWORD", PAD, 68, 0xFF7A7C84);
        }

        @Override
        String note() {
            return create ? "3-24 letters, digits, - or _; password 6+" : null;
        }

        @Override
        String button() {
            return create ? "Create account" : "Sign in";
        }

        @Override
        String busyButton() {
            return create ? "Creating..." : "Signing in...";
        }

        @Override
        String link() {
            return create ? "Have an account? Sign in" : "No account yet? Create one";
        }

        @Override
        void onLink() {
            create = !create;
            error = null;
        }

        @Override
        void submit() {
            if (busy) return;
            final String name = username.getText()
                .trim(), pass = password.text();
            if (name.isEmpty() || pass.isEmpty()) {
                error = "Type your username and password";
                return;
            }
            busy = true;
            error = null;
            Posting.signIn(name, pass, create, signedIn -> {
                busy = false;
                password.clear();
                popup.closeIfOpen();
                then.run();
            }, why -> {
                busy = false;
                error = why;
            });
        }
    }

    /** A plan to the public setups: its title, and a line about it if wanted. */
    private static final class Post extends Form {

        private final BoardSession session;
        private final Graph plan;
        private final TextFieldWidget title, description;
        @Nullable
        private String posted;
        private String postedTitle = "";

        Post(final Popup popup, final BoardSession session, final Graph plan) {
            super(popup);
            this.session = session;
            this.plan = plan;
            title = field("Title", plan.getName(), 44);
            description = field("A line about it (optional)", "", 78);
        }

        @Override
        void next() {
            getContext().focus(description);
        }

        @Override
        String title() {
            return posted != null ? "Posted!" : "Post to the public library";
        }

        @Override
        String subtitle() {
            return posted != null ? "Everyone can find it in Public setups"
                : "As " + Account.username() + ", for everyone to find";
        }

        @Override
        void labels() {
            if (posted != null) {
                // Where the fields were: what went up, as a tile.
                final int y = 40, h = 44;
                Hyb.rect(PAD, y, W - 2 * PAD, h, 0xFF2A2D33);
                Hyb.rect(PAD, y, 2, h, CYAN);
                Hyb.text(Hyb.fit(postedTitle, W - 2 * PAD - 12), PAD + 8, y + 10, 0xFFFFFFFF);
                Hyb.text(Hyb.fit("by " + Account.username(), W - 2 * PAD - 12), PAD + 8, y + 24, Hyb.MUTED);
                return;
            }
            Hyb.text("TITLE", PAD, 34, 0xFF7A7C84);
            Hyb.text("ABOUT IT", PAD, 68, 0xFF7A7C84);
        }

        @Override
        String button() {
            return posted != null ? "See it on the website" : "Post";
        }

        @Override
        String busyButton() {
            return "Posting...";
        }

        @Override
        String link() {
            return posted != null ? "Done" : "Not " + Account.username() + "? Sign out";
        }

        @Override
        void onLink() {
            if (posted != null) {
                popup.closeIfOpen();
                return;
            }
            Account.signOut();
            popup.closeIfOpen();
        }

        @Override
        void submit() {
            if (busy) return;
            if (posted != null) {
                LibraryView.openInBrowser(posted);
                return;
            }
            final String name = title.getText()
                .trim();
            if (name.isEmpty()) {
                error = "Give it a title";
                return;
            }
            if (plan.getNodes()
                .isEmpty()) {
                error = "Add a recipe first: the library takes no empty plans";
                return;
            }
            busy = true;
            error = null;
            Posting.post(
                plan,
                name.length() > 80 ? name.substring(0, 80) : name,
                description.getText()
                    .trim(),
                id -> {
                    final com.gtnhplanner.ui.card.CardModel m = session.model(id);
                    return m == null ? null : m.machines;
                },
                link -> {
                    busy = false;
                    posted = link;
                    postedTitle = name;
                    title.setEnabled(false);
                    description.setEnabled(false);
                },
                why -> {
                    busy = false;
                    error = why;
                });
        }
    }

    /** A text field for the forms: Enter submits (and only Enter), Tab moves on. */
    private static final class EnterField extends TextFieldWidget {

        private final Runnable enter, tab;

        EnterField(final Runnable enter, final Runnable tab) {
            this.enter = enter;
            this.tab = tab;
        }

        @Override
        public Result onKeyPressed(final char typedChar, final int keyCode) {
            if (isFocused() && (keyCode == org.lwjgl.input.Keyboard.KEY_RETURN
                || keyCode == org.lwjgl.input.Keyboard.KEY_NUMPADENTER)) {
                enter.run();
                return Result.SUCCESS;
            }
            if (isFocused() && keyCode == org.lwjgl.input.Keyboard.KEY_TAB) {
                tab.run();
                return Result.SUCCESS;
            }
            return super.onKeyPressed(typedChar, keyCode);
        }
    }

    /** Signed in: who, and a way out. */
    public static void signOut(final Consumer<String> told) {
        final String who = Account.username();
        Account.signOut();
        told.accept(who == null ? "Signed out" : "Signed out of " + who);
    }
}

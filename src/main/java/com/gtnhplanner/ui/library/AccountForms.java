package com.gtnhplanner.ui.library;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.importer.game.FactoryFlowImport;
import com.gtnhplanner.library.Account;
import com.gtnhplanner.library.CommunityApi;
import com.gtnhplanner.library.Posting;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.Resources;
import com.gtnhplanner.ui.popup.PickList;
import com.gtnhplanner.ui.popup.Popup;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * Signing in to the library, and posting a plan to its public setups or changing a post there: small forms in the
 * board's popup style. A title and a line of what it is about, the fields (a post's icon in a slot beside its title),
 * the reason in amber when the site said no, one cyan button (Enter presses it), and a link under it (the other way
 * round, or out).
 */
public final class AccountForms {

    private static final int W = 230, PAD = 10, FIELD_H = 16, CYAN = 0xFF22D3EE;

    private AccountForms() {}

    /** Signs in (or makes an account), then runs {@code then}. */
    public static void signIn(final ModularPanel parent, final Runnable then) {
        signIn(parent, then, null);
    }

    /** As above, saying {@code why} (in amber, where the site's reasons go) when there is one. */
    private static void signIn(final ModularPanel parent, final Runnable then, @Nullable final String why) {
        final Popup popup = new Popup("gtnhplanner_sign_in", W, 164);
        final SignIn form = new SignIn(popup, then);
        form.error = why;
        popup.child(form.size(W, 164));
        open(parent, popup, 164);
    }

    /**
     * Posts a plan with a title, a line about it if wanted, and an icon; or, for a plan already posted, updates that
     * post with the plan as it is now. Signs in first when needed.
     */
    public static void post(final ModularPanel parent, final BoardSession session, final Graph plan) {
        post(parent, session, plan, null, null);
    }

    /**
     * As above, filled in with {@code draft} (what was typed before a sign-in ran out) rather than from the plan;
     * {@code why} is said on the sign-in form when one comes first.
     */
    private static void post(final ModularPanel parent, final BoardSession session, final Graph plan,
        @Nullable final Posting.Face draft, @Nullable final String why) {
        if (!Account.signedIn()) {
            signIn(parent, () -> post(parent, session, plan, draft, null), why);
            return;
        }
        final Popup popup = new Popup("gtnhplanner_post", W, 158);
        popup.child(new Post(popup, parent, session, plan, draft).size(W, 158));
        open(parent, popup, 158);
    }

    /**
     * The menu row that shares a plan to the library, or updates what it shared: the Share key's, a tab's, a tile's.
     */
    public static PickList.Entry shareRow(final ModularPanel parent, final BoardSession session, final Graph plan) {
        final boolean shared = plan.getPostId() != null;
        return new PickList.Entry(
            null,
            shared ? "Update in library..." : "Share to library...",
            shared ? "with the plan as it is" : "for other players",
            Hyb.INK,
            false,
            () -> post(parent, session, plan));
    }

    /**
     * Changes a post of the player's own: its title, description and icon, picked from {@code choices}. {@code done}
     * gets the post as it is now.
     */
    public static void edit(final ModularPanel parent, final CommunityApi.Setup setup,
        final List<CommunityApi.Resource> choices, final Consumer<CommunityApi.Setup> done) {
        edit(parent, setup, choices, done, null, null);
    }

    /** As above, filled in with {@code draft} when a sign-in ran out on it; {@code why} as for {@link #post}. */
    private static void edit(final ModularPanel parent, final CommunityApi.Setup setup,
        final List<CommunityApi.Resource> choices, final Consumer<CommunityApi.Setup> done,
        @Nullable final Posting.Face draft, @Nullable final String why) {
        if (!Account.signedIn()) {
            signIn(parent, () -> edit(parent, setup, choices, done, draft, null), why);
            return;
        }
        final Popup popup = new Popup("gtnhplanner_edit_post", W, 158);
        popup.child(new Edit(popup, parent, setup, choices, done, draft).size(W, 158));
        open(parent, popup, 158);
    }

    /**
     * What a plan could wear as its icon, most telling first: what it makes into drawers, what its cards make, then
     * what it takes in. Never EU.
     */
    public static List<CommunityApi.Resource> choices(final Graph g) {
        final java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        if (g.getIcon() != null) keys.add(g.getIcon());
        for (final Drawer d : g.getDrawers()) if (d.getKind() == Drawer.Kind.PRODUCT) keys.add(d.getResourceKey());
        for (final Node n : g.getNodes()) for (final Port<?> p : n.outputs) keys.add(Resources.key(p));
        for (final Drawer d : g.getDrawers()) keys.add(d.getResourceKey());
        for (final Node n : g.getNodes()) for (final Port<?> p : n.inputs) keys.add(Resources.key(p));
        final List<CommunityApi.Resource> out = new ArrayList<>();
        for (final String k : keys) {
            if (k == null || k.isEmpty() || Resources.isPower(k)) continue;
            final CommunityApi.Resource r = Posting.face(k);
            if (r != null && !"power".equals(r.kind())) out.add(r);
        }
        return out;
    }

    /** A plan's face as its tile shows it: its icon, else the first thing it makes into a drawer or its cards make. */
    @Nullable
    public static String faceKey(final Graph g) {
        if (g.getIcon() != null) return g.getIcon();
        for (final Drawer d : g.getDrawers()) if (d.getKind() == Drawer.Kind.PRODUCT) return d.getResourceKey();
        for (final Node n : g.getNodes()) {
            for (final Port<?> p : n.outputs) {
                final String k = Resources.key(p);
                if (!k.isEmpty() && !Resources.isPower(k)) return k;
            }
        }
        return null;
    }

    /** One line, as the forms' fields take it: the site's descriptions may have several. */
    static String oneLine(final String s) {
        return s.replace("\r", "")
            .replace('\n', ' ')
            .trim();
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

        boolean over(final int x, final int y, final int w, final int h) {
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

    /** Signing in with the library's username and password, or making an account there. */
    private static final class SignIn extends Form {

        private final Runnable then;
        private final TextFieldWidget username;
        private final PasswordField password;
        private boolean create;
        private boolean focusedOnce;

        SignIn(final Popup popup, final Runnable then) {
            super(popup);
            this.then = then;
            final String last = Account.lastUsername();
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
            return create ? "Create a library account" : "Sign in to the library";
        }

        @Override
        String subtitle() {
            return create ? "To share plans with other players" : "To share plans and edit your posts";
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

    /**
     * A post's face as a form: its icon (a slot beside the title; click it to pick one), its title, and a line about
     * it.
     */
    private abstract static class FaceForm extends Form {

        private static final int SLOT = 16;

        final TextFieldWidget title, description;
        @Nullable
        CommunityApi.Resource icon;
        /** The description as it came in, which may run over several lines: kept unless the field is changed. */
        private final String fullDescription;

        FaceForm(final Popup popup, final String initialTitle, final String initialDescription,
            @Nullable final CommunityApi.Resource initialIcon) {
            super(popup);
            fullDescription = initialDescription;
            icon = initialIcon;
            title = field("Title", initialTitle, 44);
            title.pos(PAD + SLOT + 4, 44)
                .size(W - 2 * PAD - SLOT - 4, FIELD_H);
            description = field("A line about it (optional)", oneLine(initialDescription), 78);
        }

        /** What could be picked as the icon. */
        abstract List<CommunityApi.Resource> choices();

        @Override
        void next() {
            getContext().focus(description);
        }

        @Override
        void labels() {
            Hyb.text("ICON AND TITLE", PAD, 34, 0xFF7A7C84);
            Hyb.text("ABOUT IT", PAD, 68, 0xFF7A7C84);
            final boolean hot = !busy && over(PAD, 44, SLOT, SLOT);
            Hyb.rect(PAD, 44, SLOT, SLOT, hot ? CYAN : 0xFF4A4C54);
            Hyb.rect(PAD + 1, 45, SLOT - 2, SLOT - 2, 0xFF1B1D21);
            if (!drawIcon(icon, PAD, 44, SLOT))
                Hyb.textCentered("+", PAD + SLOT / 2f + 0.5f, 48, hot ? CYAN : Hyb.MUTED);
        }

        /** Draws a face at {@code size}; false when there is nothing this game can show. */
        boolean drawIcon(@Nullable final CommunityApi.Resource r, final int x, final int y, final int size) {
            if (r == null) return false;
            final float z = getContext().getCurrentDrawingZ();
            if (r.fluid()) {
                final FluidStack f = FactoryFlowImport.fluid(r.id());
                if (f == null) return false;
                Hyb.icon(null, f, x, y, size, z);
            } else {
                final ItemStack i = FactoryFlowImport.item(r.id());
                if (i == null) return false;
                Hyb.icon(i, null, x, y, size, z);
            }
            return true;
        }

        @Override
        public Result onMousePressed(final int mouseButton) {
            if (mouseButton == 0 && !busy && over(PAD, 44, SLOT, SLOT)) {
                Hyb.click();
                pickIcon();
                return Result.SUCCESS;
            }
            return super.onMousePressed(mouseButton);
        }

        /** The choices as a list under the slot, "No icon" first when there is one. */
        private void pickIcon() {
            final List<PickList.Entry> rows = new ArrayList<>();
            if (icon != null) rows.add(new PickList.Entry(null, "No icon", "", Hyb.MUTED, false, () -> icon = null));
            for (final CommunityApi.Resource r : choices()) {
                final boolean current = icon != null && icon.kind()
                    .equals(r.kind())
                    && icon.id()
                        .equals(r.id());
                rows.add(new PickList.Entry(rowIcon(r), r.name(), "", Hyb.INK, current, () -> icon = r));
            }
            if (rows.isEmpty()) {
                error = "Nothing to pick: the plan has no items or fluids yet";
                return;
            }
            Popup.open(
                getPanel(),
                PickList.popup("gtnhplanner_post_icon", "Its icon", rows, rows.size() > 10, 170),
                getArea().x + PAD,
                getArea().y + 44 + SLOT + 2);
        }

        /** A list row's icon: the item, or the fluid as NEI shows it. */
        @Nullable
        private static ItemStack rowIcon(final CommunityApi.Resource r) {
            if (!r.fluid()) return FactoryFlowImport.item(r.id());
            return Resources.lookupStack(null, FactoryFlowImport.fluid(r.id()));
        }

        /** The form's face, its title cut to the site's 80 letters; the long description kept when left alone. */
        Posting.Face face() {
            String name = title.getText()
                .trim();
            if (name.length() > 80) name = name.substring(0, 80);
            String about = description.getText()
                .trim();
            if (about.equals(oneLine(fullDescription))) about = fullDescription.trim();
            return new Posting.Face(name, about, icon);
        }

        void lock() {
            title.setEnabled(false);
            description.setEnabled(false);
        }
    }

    /**
     * A plan to the public setups: its icon, title, and a line about it if wanted. A plan already posted updates its
     * post instead (the plan as it is now, and its face), unless asked to post a new one.
     */
    private static final class Post extends FaceForm {

        private final ModularPanel parent;
        private final BoardSession session;
        private final Graph plan;
        /** Whether this updates the plan's post rather than making a new one. */
        private boolean update;
        private boolean posted, wasUpdate;

        Post(final Popup popup, final ModularPanel parent, final BoardSession session, final Graph plan,
            @Nullable final Posting.Face draft) {
            super(
                popup,
                draft != null ? draft.title() : plan.getName(),
                draft != null ? draft.description() : plan.getDescription(),
                draft != null ? draft.icon() : Posting.face(faceKey(plan)));
            this.parent = parent;
            this.session = session;
            this.plan = plan;
            update = plan.getPostId() != null;
        }

        @Override
        List<CommunityApi.Resource> choices() {
            return AccountForms.choices(plan);
        }

        @Override
        String title() {
            if (posted) return wasUpdate ? "Updated!" : "Shared!";
            return update ? "Update your post" : "Share to the public library";
        }

        @Override
        String subtitle() {
            if (posted) return "Everyone can find it in Public setups";
            return update ? "With the plan as it is now; its votes stay"
                : "As " + Account.username() + ", for everyone to find";
        }

        @Override
        void labels() {
            if (!posted) {
                super.labels();
                return;
            }
            // Where the fields were: what went up, as a tile.
            final int y = 40, h = 44;
            Hyb.rect(PAD, y, W - 2 * PAD, h, 0xFF2A2D33);
            Hyb.rect(PAD, y, 2, h, CYAN);
            final int tx = drawIcon(icon, PAD + 8, y + 10, 24) ? PAD + 38 : PAD + 8;
            final Posting.Face f = face();
            Hyb.text(Hyb.fit(f.title(), W - PAD - 6 - tx), tx, y + 10, 0xFFFFFFFF);
            Hyb.text(Hyb.fit("by " + Account.username(), W - PAD - 6 - tx), tx, y + 24, Hyb.MUTED);
        }

        @Override
        String button() {
            if (posted) return "Done";
            return update ? "Update post" : "Share";
        }

        @Override
        String busyButton() {
            return update ? "Updating..." : "Sharing...";
        }

        @Override
        @Nullable
        String link() {
            if (posted) return null;
            return update ? "Share it as a new one instead" : "Not " + Account.username() + "? Sign out";
        }

        @Override
        void onLink() {
            if (update) {
                update = false;
                error = null;
                return;
            }
            Account.signOut();
            popup.closeIfOpen();
        }

        @Override
        void submit() {
            if (busy) return;
            if (posted) {
                popup.closeIfOpen();
                return;
            }
            final Posting.Face f = face();
            if (f.title()
                .isEmpty()) {
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
            Posting.post(plan, f, update, id -> {
                final com.gtnhplanner.ui.card.CardModel m = session.model(id);
                return m == null ? null : m.machines;
            }, () -> {
                busy = false;
                wasUpdate = update;
                posted = true;
                lock();
            }, why -> {
                busy = false;
                error = why;
                // The post was gone: the plan forgot it, so the next press posts a new one.
                if (plan.getPostId() == null) update = false;
                // The sign-in ran out: sign in again, then back here with what was typed.
                if (!Account.signedIn()) {
                    popup.closeIfOpen();
                    post(parent, session, plan, f, why);
                }
            });
        }
    }

    /** A post of the player's own, from the library: its icon, title and description. Its plan stays. */
    private static final class Edit extends FaceForm {

        private final ModularPanel parent;
        private final CommunityApi.Setup setup;
        private final List<CommunityApi.Resource> choices;
        private final Consumer<CommunityApi.Setup> done;

        Edit(final Popup popup, final ModularPanel parent, final CommunityApi.Setup setup,
            final List<CommunityApi.Resource> choices, final Consumer<CommunityApi.Setup> done,
            @Nullable final Posting.Face draft) {
            super(
                popup,
                draft != null ? draft.title() : setup.name(),
                draft != null ? draft.description() : setup.description(),
                draft != null ? draft.icon() : setup.icon());
            this.parent = parent;
            this.setup = setup;
            this.choices = choices;
            this.done = done;
        }

        @Override
        List<CommunityApi.Resource> choices() {
            return choices;
        }

        @Override
        String title() {
            return "Edit your post";
        }

        @Override
        String subtitle() {
            return "Its plan, votes and comments stay";
        }

        @Override
        String button() {
            return "Save";
        }

        @Override
        String busyButton() {
            return "Saving...";
        }

        @Override
        String link() {
            return "Cancel";
        }

        @Override
        void onLink() {
            popup.closeIfOpen();
        }

        @Override
        void submit() {
            if (busy) return;
            final Posting.Face f = face();
            if (f.title()
                .isEmpty()) {
                error = "Give it a title";
                return;
            }
            busy = true;
            error = null;
            Posting.edit(setup, f, edited -> {
                busy = false;
                popup.closeIfOpen();
                done.accept(edited);
            }, why -> {
                busy = false;
                error = why;
                if (!Account.signedIn()) {
                    popup.closeIfOpen();
                    edit(parent, setup, choices, done, f, why);
                }
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

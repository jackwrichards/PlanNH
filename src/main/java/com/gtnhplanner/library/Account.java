package com.gtnhplanner.library;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;

import com.gtnhplanner.GtnhPlanner;

/**
 * The player's gtnhplanner.com account in this game: who is signed in and the session the site gave them, kept in
 * the config folder so it lasts as long as the website's own sign-in does (180 days). The password is never kept.
 * Also this game's device id, which the site asks for with every post.
 */
public final class Account {

    private static final String SESSION = "session", USERNAME = "username", DEVICE = "device";

    private static Properties props;

    private Account() {}

    @Nullable
    static String token() {
        return load().getProperty(SESSION);
    }

    /** Who is signed in, or null. */
    @Nullable
    public static String username() {
        return token() == null ? null : load().getProperty(USERNAME);
    }

    public static boolean signedIn() {
        return token() != null;
    }

    static void signedIn(final CommunityApi.SignedIn s) {
        load().setProperty(SESSION, s.token());
        load().setProperty(USERNAME, s.username());
        save();
    }

    public static void signOut() {
        load().remove(SESSION);
        load().remove(USERNAME);
        save();
    }

    /** This game's id for the site, made once. */
    static String deviceId() {
        String id = load().getProperty(DEVICE);
        if (id == null) {
            id = "gtnhplanner-" + UUID.randomUUID();
            load().setProperty(DEVICE, id);
            save();
        }
        return id;
    }

    private static File file() {
        return new File(Minecraft.getMinecraft().mcDataDir, "config/gtnhplanner-account.properties");
    }

    private static synchronized Properties load() {
        if (props != null) return props;
        props = new Properties();
        // Read from the name before the rename until the new one has been written.
        File f = file();
        if (!f.isFile()) f = new File(f.getParentFile(), "plannh-account.properties");
        if (!f.isFile()) return props;
        try (Reader in = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            props.load(in);
        } catch (final IOException e) {
            GtnhPlanner.LOG.warn("Could not read the account file", e);
        }
        return props;
    }

    private static synchronized void save() {
        final File f = file(), dir = f.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        try (Writer out = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            props.store(
                out,
                "GTNH Planner: your gtnhplanner.com sign-in (a session, never your password). Delete to sign out.");
        } catch (final IOException e) {
            GtnhPlanner.LOG.warn("Could not write the account file", e);
        }
    }
}

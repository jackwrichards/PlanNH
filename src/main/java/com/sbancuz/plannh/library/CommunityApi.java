package com.sbancuz.plannh.library;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sbancuz.plannh.Tags;

/**
 * Factory Flow's public setups (gtnhplanner.com's community hub), read the way the website reads them: the list, and
 * a setup's whole plan. Nothing here needs an account. Blocking calls: run them off the client thread.
 * {@code -Dplannh.library.url} points it at another copy of the site, a local one for testing.
 */
public final class CommunityApi {

    private static volatile String site = System.getProperty("plannh.library.url", "https://gtnhplanner.com");

    /** The site the library reads and posts to: gtnhplanner.com, or a test copy. */
    public static String site() {
        return site;
    }

    /** Points the library at another copy of the site (the dev harness's local stand-in). */
    public static void useSite(final String url) {
        site = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static final int CONNECT_MS = 8_000, READ_MS = 25_000;

    private CommunityApi() {}

    /** One item or fluid a setup makes or needs, at its rate (items/s or L/s). */
    public record Resource(String kind, String id, String name, double perSecond) {

        public boolean fluid() {
            return "fluid".equals(kind);
        }
    }

    /** A shared setup as the list shows it: who, what, how big, and how it was received. */
    public record Setup(String id, String name, String description, String author, String gameVersion,
        List<String> tags, @Nullable Resource icon, List<Resource> needs, List<Resource> outputs, double euPerTick,
        int machines, int nodes, String tier, int tierIndex, int upvotes, int downvotes, int downloads, int comments,
        String created, String active) {

        /** The website's own page for it. */
        public String link() {
            return site + "/?plan=" + id;
        }
    }

    /** What to list. {@code maxTierIndex} -1 and empty strings mean any. */
    public record Query(String search, String sort, int maxTierIndex, String gameVersion, List<String> makes,
        boolean mine) {

        public static Query start() {
            return new Query("", "top", -1, "", List.of(), false);
        }

        public Query withSearch(final String s) {
            return new Query(s, sort, maxTierIndex, gameVersion, makes, mine);
        }

        public Query withSort(final String s) {
            return new Query(search, s, maxTierIndex, gameVersion, makes, mine);
        }

        public Query withMaxTier(final int t) {
            return new Query(search, sort, t, gameVersion, makes, mine);
        }

        public Query withVersion(final String v) {
            return new Query(search, sort, maxTierIndex, v, makes, mine);
        }

        public Query withMakes(final List<String> m) {
            return new Query(search, sort, maxTierIndex, gameVersion, m, mine);
        }

        /** Only the signed-in player's own posts. */
        public Query withMine(final boolean b) {
            return new Query(search, sort, maxTierIndex, gameVersion, makes, b);
        }
    }

    /** One page of the list, with the total and every pack version the hub holds. */
    public record Page(List<Setup> setups, int total, int page, List<String> gameVersions) {}

    /** A setup's plan: Factory Flow's project JSON, as the importer reads it. */
    public record Download(String name, String planJson) {}

    public static Page list(final Query q, final int page, final int pageSize) throws IOException {
        final StringBuilder url = new StringBuilder(site).append("/api/community/plans?sort=")
            .append(enc(q.sort()))
            .append("&page=")
            .append(page)
            .append("&pageSize=")
            .append(pageSize);
        if (!q.search()
            .isBlank())
            url.append("&search=")
                .append(enc(q.search()));
        if (q.maxTierIndex() >= 0) url.append("&maxTierIndex=")
            .append(q.maxTierIndex());
        if (!q.gameVersion()
            .isEmpty())
            url.append("&gameVersion=")
                .append(enc(q.gameVersion()));
        if (!q.makes()
            .isEmpty())
            url.append("&makes=")
                .append(enc(String.join(",", q.makes())));
        if (q.mine()) url.append("&mine=1");
        final JsonObject root = json(request("GET", url.toString(), null, q.mine() ? Account.token() : null));
        final List<Setup> setups = new ArrayList<>();
        for (final JsonElement e : array(root, "plans")) setups.add(setup(e.getAsJsonObject()));
        final List<String> versions = new ArrayList<>();
        for (final JsonElement e : array(root, "gameVersions")) versions.add(e.getAsString());
        return new Page(setups, integer(root, "total"), integer(root, "page"), versions);
    }

    /** A setup's whole plan. The site counts it as a download, as when the website opens one. */
    public static Download download(final String id) throws IOException {
        final JsonObject root = json(
            request("POST", site + "/api/community/plans/" + enc(id) + "/download", null, null));
        final JsonElement plan = root.get("plan");
        if (plan == null || !plan.isJsonObject()) throw new IOException("the site sent no plan");
        return new Download(string(root, "name"), plan.toString());
    }

    // region Accounts and posting: the website's own username and password accounts and its session

    /** A signed-in account, and the session the site gave it (its {@value #SESSION_COOKIE} cookie). */
    public record SignedIn(String username, String token) {}

    private static final String SESSION_COOKIE = "gtnh_session";

    /** Signs in; the reason, in the site's words, when it says no. */
    public static SignedIn signIn(final String username, final String password) throws IOException {
        return account("/api/community/auth/login", username, password);
    }

    /** Makes an account and signs in to it. */
    public static SignedIn register(final String username, final String password) throws IOException {
        return account("/api/community/auth/register", username, password);
    }

    private static SignedIn account(final String path, final String username, final String password)
        throws IOException {
        final JsonObject body = new JsonObject();
        body.addProperty("username", username);
        body.addProperty("password", password);
        final Response r = send("POST", site + path, body.toString(), null);
        final String token = r.cookie(SESSION_COOKIE);
        if (token == null || token.isEmpty()) throw new IOException("the site did not sign you in");
        return new SignedIn(string(json(r.body()), "username"), token);
    }

    /** Who a session belongs to, or null when it has run out. */
    @Nullable
    public static String whoIs(final String token) throws IOException {
        final JsonElement user = json(request("GET", site + "/api/community/auth/me", null, token)).get("user");
        return user != null && user.isJsonObject() ? string(user.getAsJsonObject(), "username") : null;
    }

    /** What a post needs: its title and the plan, and where it came from. */
    public record Post(String name, String description, String gameVersion, String deviceId, JsonObject plan) {}

    /** Posts a plan to the public setups; its id on the site. */
    public static String post(final String token, final Post p) throws IOException {
        final JsonObject body = new JsonObject();
        body.addProperty("name", p.name());
        body.addProperty("description", p.description());
        body.addProperty("gameVersion", p.gameVersion());
        body.addProperty("datasetVersionId", "");
        body.addProperty("deviceId", p.deviceId());
        body.add("plan", p.plan());
        return string(json(request("POST", site + "/api/community/plans", body.toString(), token)), "id");
    }

    // endregion

    // region JSON

    private static Setup setup(final JsonObject o) {
        final List<String> tags = new ArrayList<>();
        for (final JsonElement t : array(o, "tags")) tags.add(t.getAsString());
        final JsonElement icon = o.get("icon");
        return new Setup(
            string(o, "id"),
            string(o, "name"),
            string(o, "description"),
            string(o, "authorName"),
            string(o, "gameVersion"),
            tags,
            icon != null && icon.isJsonObject() ? resource(icon.getAsJsonObject()) : null,
            resources(o, "needs"),
            resources(o, "outputs"),
            number(o, "totalEuT"),
            integer(o, "machineCount"),
            integer(o, "nodeCount"),
            string(o, "highestTier"),
            o.has("highestTierIndex") ? integer(o, "highestTierIndex") : -1,
            integer(o, "upvotes"),
            integer(o, "downvotes"),
            integer(o, "downloads"),
            integer(o, "commentCount"),
            string(o, "createdAt"),
            o.has("lastActivityAt") ? string(o, "lastActivityAt") : string(o, "createdAt"));
    }

    private static List<Resource> resources(final JsonObject o, final String key) {
        final List<Resource> out = new ArrayList<>();
        for (final JsonElement e : array(o, key)) if (e.isJsonObject()) out.add(resource(e.getAsJsonObject()));
        return out;
    }

    private static Resource resource(final JsonObject o) {
        return new Resource(
            string(o, "kind"),
            string(o, "resourceId"),
            string(o, "displayName"),
            number(o, "ratePerSecond"));
    }

    private static JsonArray array(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    private static String string(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static double number(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? 0 : e.getAsDouble();
    }

    private static int integer(final JsonObject o, final String key) {
        return (int) Math.round(number(o, key));
    }

    private static JsonObject json(final String body) throws IOException {
        try {
            return new JsonParser().parse(body)
                .getAsJsonObject();
        } catch (final RuntimeException e) {
            throw new IOException("the site sent something that is not JSON", e);
        }
    }

    // endregion

    /** An answer: its status, body and the session cookie it set, if any. */
    private record Response(int status, String body, List<String> cookies) {

        @Nullable
        String cookie(final String name) {
            for (final String c : cookies) {
                if (c.startsWith(name + "=")) return c.substring(name.length() + 1)
                    .split(";", 2)[0];
            }
            return null;
        }
    }

    /** The body of an answer that went well; else an IOException with the site's own reason. */
    private static String request(final String method, final String url, @Nullable final String json,
        @Nullable final String token) throws IOException {
        return send(method, url, json, token).body();
    }

    private static Response send(final String method, final String url, @Nullable final String json,
        @Nullable final String token) throws IOException {
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(CONNECT_MS);
        c.setReadTimeout(READ_MS);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "PlanNH/" + Tags.VERSION + " (GT New Horizons planner mod)");
        if (token != null) c.setRequestProperty("Cookie", SESSION_COOKIE + "=" + token);
        final byte[] out = json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8);
        if ("POST".equals(method)) {
            c.setDoOutput(true);
            if (json != null) c.setRequestProperty("Content-Type", "application/json");
            c.setFixedLengthStreamingMode(out.length);
            try (java.io.OutputStream o = c.getOutputStream()) {
                o.write(out);
            }
        }
        try {
            final int status = c.getResponseCode();
            final InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            final String body = in == null ? "" : read(in);
            if (status >= 400) throw new IOException(reason(status, body));
            final List<String> cookies = new ArrayList<>();
            for (final java.util.Map.Entry<String, List<String>> h : c.getHeaderFields()
                .entrySet()) {
                if (h.getKey() != null && h.getKey()
                    .equalsIgnoreCase("Set-Cookie")) cookies.addAll(h.getValue());
            }
            return new Response(status, body, cookies);
        } finally {
            c.disconnect();
        }
    }

    /** Why the site said no: its own words when it gave them ({@code {"error": ...}}), else the status. */
    private static String reason(final int status, final String body) {
        try {
            final JsonElement e = new JsonParser().parse(body);
            if (e.isJsonObject() && e.getAsJsonObject()
                .has("error")) {
                return e.getAsJsonObject()
                    .get("error")
                    .getAsString();
            }
        } catch (final RuntimeException ignored) {
            // Not JSON: fall through to the status.
        }
        return switch (status) {
            case 401 -> "not signed in";
            case 404 -> "not found";
            case 429 -> "too many tries; wait a little";
            default -> "the site answered " + status;
        };
    }

    private static String read(final InputStream in) throws IOException {
        try (InputStream s = in) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buf = new byte[16384];
            for (int n; (n = s.read(buf)) > 0;) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String enc(final String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
        } catch (final java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}

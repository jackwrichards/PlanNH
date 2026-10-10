package com.gtnhplanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.library.CommunityApi;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Editing and deleting the player's own posts, and their icons, against a stand-in for gtnhplanner.com on this
 * machine: the calls go as the website's own (method, path, session cookie, fields) and its answers come back right.
 */
class CommunityApiTest {

    /** One request as the stand-in saw it. */
    private record Seen(String method, String path, String cookie, String body) {}

    private HttpServer server;
    private final List<Seen> seen = new ArrayList<>();
    /** Path (with its query) to the status and body to answer with. */
    private final Map<String, String[]> answers = new ConcurrentHashMap<>();
    private final String before = CommunityApi.site();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::answer);
        server.start();
        CommunityApi.useSite(
            "http://127.0.0.1:" + server.getAddress()
                .getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
        CommunityApi.useSite(before);
    }

    private void answer(final HttpExchange ex) throws IOException {
        final String path = ex.getRequestURI()
            .toString();
        synchronized (seen) {
            seen.add(
                new Seen(
                    ex.getRequestMethod(),
                    path,
                    ex.getRequestHeaders()
                        .getFirst("Cookie"),
                    new String(
                        ex.getRequestBody()
                            .readAllBytes(),
                        StandardCharsets.UTF_8)));
        }
        String[] a = answers.get(path);
        if (a == null) a = answers.get(
            ex.getRequestURI()
                .getPath());
        if (a == null) a = new String[] { "404", "{\"error\":\"Plan not found.\"}" };
        final byte[] out = a[1].getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders()
            .add("Content-Type", "application/json");
        ex.sendResponseHeaders(Integer.parseInt(a[0]), out.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(out);
        }
    }

    private Seen last() {
        synchronized (seen) {
            return seen.get(seen.size() - 1);
        }
    }

    @Test
    void theListMarksThePlayersOwnPosts() throws IOException {
        answers.put(
            "/api/community/plans",
            new String[] { "200",
                "{\"plans\":[{\"id\":\"a\",\"name\":\"Mine\",\"isMine\":true},"
                    + "{\"id\":\"b\",\"name\":\"Theirs\",\"isMine\":false},{\"id\":\"c\",\"name\":\"Old site\"}],"
                    + "\"total\":3,\"page\":1}" });
        final CommunityApi.Page page = CommunityApi.list(CommunityApi.Query.start(), 1, 48, "tok");
        assertEquals("gtnh_session=tok", last().cookie(), "signed in, the list goes with the session");
        assertTrue(
            page.setups()
                .get(0)
                .mine());
        assertFalse(
            page.setups()
                .get(1)
                .mine());
        assertFalse(
            page.setups()
                .get(2)
                .mine(),
            "a site that does not say is taken as not");
        CommunityApi.list(CommunityApi.Query.start(), 1, 48, null);
        assertEquals(null, last().cookie(), "signed out, without one");
    }

    @Test
    void anEditSendsItsFieldsToThePost() throws IOException {
        answers.put("/api/community/plans/p1", new String[] { "200", "{\"id\":\"p1\"}" });
        final JsonObject fields = new JsonObject();
        fields.addProperty("name", "Steel line");
        fields.addProperty("description", "Two\nlines");
        fields.add("icon", JsonNull.INSTANCE);
        CommunityApi.update("tok", "p1", fields);
        final Seen s = last();
        assertEquals("PUT", s.method());
        assertEquals("/api/community/plans/p1", s.path());
        assertEquals("gtnh_session=tok", s.cookie());
        final JsonObject body = new JsonParser().parse(s.body())
            .getAsJsonObject();
        assertEquals(
            "Steel line",
            body.get("name")
                .getAsString());
        assertEquals(
            "Two\nlines",
            body.get("description")
                .getAsString());
        assertTrue(
            body.has("icon") && body.get("icon")
                .isJsonNull(),
            "a null icon goes, to clear it");
        assertFalse(body.has("plan"), "only what was given");
    }

    @Test
    void aDeleteTakesThePostDown() throws IOException {
        answers.put("/api/community/plans/p2", new String[] { "200", "{\"ok\":true}" });
        CommunityApi.delete("tok", "p2");
        assertEquals("DELETE", last().method());
        assertEquals("/api/community/plans/p2", last().path());
        assertEquals("gtnh_session=tok", last().cookie());
    }

    @Test
    void aPostThatIsGoneOrNotTheirsSaysSo() {
        final CommunityApi.Refused gone = assertThrows(
            CommunityApi.Refused.class,
            () -> CommunityApi.delete("tok", "nope"));
        assertEquals(404, gone.status);
        assertTrue(gone.postGone());
        assertEquals("Plan not found.", gone.getMessage(), "in the site's own words");
        answers.put("/api/community/plans/theirs", new String[] { "403", "{\"error\":\"You don't own this post.\"}" });
        final CommunityApi.Refused theirs = assertThrows(
            CommunityApi.Refused.class,
            () -> CommunityApi.update("tok", "theirs", new JsonObject()));
        assertTrue(theirs.postGone(), "not theirs: the link to it is forgotten as well");
        answers.put("/api/community/plans/x", new String[] { "401", "{\"error\":\"Sign in to update your post.\"}" });
        final CommunityApi.Refused out = assertThrows(
            CommunityApi.Refused.class,
            () -> CommunityApi.update("tok", "x", new JsonObject()));
        assertFalse(out.postGone(), "signed out is not gone");
    }

    @Test
    void anIconComesWithTheWebsitesPictureOfIt() {
        answers.put(
            "/datasets/gtnh/datasets.manifest.json",
            new String[] { "200", "{\"versions\":[{\"id\":\"v29\",\"gtnhVersion\":\"2.9.0-beta-2\"},"
                + "{\"id\":\"v28\",\"gtnhVersion\":\"2.8.4\"}]}" });
        answers.put(
            "/api/datasets/v28/resources?kind=item&limit=40&query=Iron+Ingot",
            new String[] { "200",
                "{\"resources\":[{\"kind\":\"item\",\"id\":\"gregtech:pig_iron\","
                    + "\"iconPath\":\"/wrong.png\"},{\"kind\":\"item\",\"id\":\"minecraft:iron_ingot\","
                    + "\"iconPath\":\"/iron.png\",\"dominantColor\":\"#727272\"}]}" });
        final JsonObject icon = CommunityApi.icon("item", "minecraft:iron_ingot", "Iron Ingot", "2.8.4");
        assertEquals(
            "minecraft:iron_ingot",
            icon.get("resourceId")
                .getAsString());
        assertEquals(
            "Iron Ingot",
            icon.get("displayName")
                .getAsString());
        assertEquals(
            "/iron.png",
            icon.get("iconPath")
                .getAsString(),
            "the picture of the same id, not the first one found");
        assertEquals(
            "#727272",
            icon.get("dominantColor")
                .getAsString());
        assertTrue(
            last().path()
                .startsWith("/api/datasets/v28/"),
            "the pack's own version's list");

        final JsonObject rc = CommunityApi.icon("item", "minecraft:iron_ingot", "Iron Ingot", "2.9.0-RC-2");
        assertTrue(
            last().path()
                .startsWith("/api/datasets/v29/"),
            "a release candidate reads its version's list");
        assertFalse(rc.has("iconPath"), "not in that list: the icon goes without a picture");
        assertEquals(
            "item",
            rc.get("kind")
                .getAsString());
    }
}

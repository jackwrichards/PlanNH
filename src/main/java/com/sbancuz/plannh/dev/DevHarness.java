package com.sbancuz.plannh.dev;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundCategory;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.input.Mouse;

import com.cleanroommc.modularui.api.IMuiScreen;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.viewport.LocatedWidget;
import com.cleanroommc.modularui.widget.sizer.Area;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sbancuz.plannh.PlanNH;
import com.sbancuz.plannh.ui.gt.MultiblockPictures;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Dev-only automation so the client can be driven from a shell: auto-loads a creative test world and serves a
 * localhost HTTP endpoint for screenshots, synthetic input and widget dumps. Only active in a deobfuscated dev
 * environment (override with {@code -Dplannh.dev=true|false}). See {@code docs/dev-harness.md}.
 */
public final class DevHarness {

    private static final int PORT = Integer.getInteger("plannh.dev.port", 25599);
    private static final String WORLD = System.getProperty("plannh.dev.world", "plannh-dev");
    private static final long REQUEST_TIMEOUT_SECONDS = 30;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private final Minecraft mc = Minecraft.getMinecraft();
    /** Input steps, one per client tick, so the game sees each as a separate event batch. */
    private final Queue<Runnable> tickActions = new ConcurrentLinkedQueue<>();
    /** Work that needs a fully drawn frame, run at the end of the render tick. */
    private final Queue<Runnable> frameActions = new ConcurrentLinkedQueue<>();
    private boolean worldRequested;
    private volatile boolean ready;

    private DevHarness() {}

    public static void initIfDev() {
        if (!isEnabled()) return;
        final DevHarness harness = new DevHarness();
        FMLCommonHandler.instance()
            .bus()
            .register(harness);
        MinecraftForge.EVENT_BUS.register(harness);
        harness.startServer();
    }

    private static boolean isEnabled() {
        final String prop = System.getProperty("plannh.dev");
        if (prop != null) return Boolean.parseBoolean(prop);
        return Boolean.TRUE.equals(Launch.blackboard.get("fml.deobfuscatedEnvironment"));
    }

    // region Game hooks

    @SubscribeEvent
    public void onGuiOpen(final GuiOpenEvent event) {
        if (!(event.gui instanceof GuiMainMenu) || worldRequested) return;
        worldRequested = true;
        // The harness drives an unfocused window; a pause menu would steal every screen.
        mc.gameSettings.pauseOnLostFocus = false;
        if (WORLD.isEmpty()) {
            ready = true;
            return;
        }
        tickActions.add(this::loadWorld);
    }

    @SubscribeEvent
    public void onClientTick(final TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        watchTheUser();
        if (!ready && mc.theWorld != null && mc.thePlayer != null) ready = true;
        // Input is read per tick but hover is worked out per frame: after a stall the game runs several ticks in one
        // frame, and a press queued right after a move would land on what was under the mouse before it. So at most one
        // step per drawn frame.
        if (!framedSinceStep) return;
        final Runnable action = tickActions.poll();
        if (action == null) return;
        framedSinceStep = false;
        action.run();
    }

    private volatile boolean framedSinceStep = true;

    // When the person at the keyboard last did something: the mouse moved, a button or a key went down. Input the
    // harness sends is not theirs, so anything within a second of it is left out.
    private volatile long lastUserMs = System.currentTimeMillis();
    private volatile long lastSyntheticMs;
    private int lastMouseX = -1, lastMouseY = -1;
    private boolean lastAnyDown;

    private void watchTheUser() {
        if (!org.lwjgl.opengl.Display.isActive()) return;
        final int x = org.lwjgl.input.Mouse.getX(), y = org.lwjgl.input.Mouse.getY();
        boolean down = org.lwjgl.input.Mouse.isButtonDown(0) || org.lwjgl.input.Mouse.isButtonDown(1)
            || org.lwjgl.input.Mouse.isButtonDown(2);
        for (int k = 1; k < 256 && !down; k++) down = org.lwjgl.input.Keyboard.isKeyDown(k);
        final boolean changed = x != lastMouseX || y != lastMouseY || down && !lastAnyDown;
        lastMouseX = x;
        lastMouseY = y;
        lastAnyDown = down;
        final long now = System.currentTimeMillis();
        if (changed && now - lastSyntheticMs > 1000) lastUserMs = now;
    }

    @SubscribeEvent
    public void onRenderTick(final TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        framedSinceStep = true;
        // Only what was queued before this frame; actions may re-queue themselves for the next one.
        for (int i = frameActions.size(); i > 0; i--) {
            final Runnable action = frameActions.poll();
            if (action != null) action.run();
        }
    }

    private void loadWorld() {
        WorldSettings settings = null;
        if (mc.getSaveLoader()
            .getWorldInfo(WORLD) == null) {
            settings = new WorldSettings(0L, WorldSettings.GameType.CREATIVE, false, false, WorldType.FLAT);
            settings.enableCommands();
        }
        PlanNH.LOG.info("[dev] Loading test world '{}'", WORLD);
        mc.displayGuiScreen(null);
        mc.launchIntegratedServer(WORLD, WORLD, settings);
    }

    // endregion

    // region HTTP server

    private void startServer() {
        try {
            final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
            server.setExecutor(Executors.newSingleThreadExecutor(r -> {
                final Thread t = new Thread(r, "PlanNH-DevHarness");
                t.setDaemon(true);
                return t;
            }));
            server.createContext("/", this::handle);
            server.start();
            PlanNH.LOG.info("[dev] Harness listening on http://127.0.0.1:{}/", PORT);
            // Killing the gradle run task does not kill the game; tools/dev/mc.sh uses this as a fallback.
            final File pidFile = new File(mc.mcDataDir, "plannh-dev.pid");
            Files.writeString(
                pidFile.toPath(),
                Long.toString(
                    ProcessHandle.current()
                        .pid()));
            pidFile.deleteOnExit();
        } catch (final IOException e) {
            PlanNH.LOG.error("[dev] Harness failed to start on port {}", PORT, e);
        }
    }

    private void handle(final HttpExchange exchange) throws IOException {
        int code = 200;
        Object body;
        try {
            final Map<String, String> q = query(exchange);
            body = route(
                exchange.getRequestURI()
                    .getPath(),
                q);
        } catch (final IllegalArgumentException e) {
            code = 400;
            body = error(e.getMessage());
        } catch (final Exception e) {
            code = 500;
            body = error(e.toString());
            PlanNH.LOG.warn("[dev] Request failed", e);
        }
        final byte[] bytes = GSON.toJson(body)
            .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders()
            .set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private Object route(final String path, final Map<String, String> q) throws Exception {
        switch (path) {
            case "/":
            case "/help":
                return help();
            case "/status":
                return onClient(this::status);
            case "/open":
                requireWorld();
                return onClient(() -> {
                    openFlowchart();
                    return ok();
                });
            case "/close":
                return onClient(() -> {
                    mc.displayGuiScreen(null);
                    return ok();
                });
            case "/screenshot":
                return screenshot(q.getOrDefault("name", "harness-" + System.currentTimeMillis() + ".png"), q);
            case "/widgets":
                return onClient(this::widgets);
            case "/move":
                return input(List.of(() -> moveTo(intArg(q, "x"), intArg(q, "y"))));
            case "/click":
                return click(q);
            case "/drag":
                return drag(q);
            case "/scroll":
                return scroll(q);
            case "/key":
                return key(q);
            case "/type":
                return type(q);
            case "/cmd":
                requireWorld();
                return onClient(() -> {
                    final String cmd = arg(q, "c");
                    final int handled = ClientCommandHandler.instance.executeCommand(mc.thePlayer, cmd);
                    if (handled == 0) mc.thePlayer.sendChatMessage(cmd);
                    return ok();
                });
            case "/addrecipe":
                requireWorld();
                return onClient(
                    () -> DevRecipes.addRecipe(
                        arg(q, "output"),
                        q.getOrDefault("handler", ""),
                        q.getOrDefault("input", ""),
                        intArg(q, "x", 200),
                        intArg(q, "y", 200)));
            case "/frame":
                return onClient(() -> {
                    final ModularScreen mui = muiScreen();
                    final Map<String, Object> r = new LinkedHashMap<>();
                    if (mui == null) return error("no ModularUI screen");
                    try {
                        mui.onFrameUpdate();
                    } catch (final Throwable t) {
                        PlanNH.LOG.warn("[dev] frame update failed", t);
                        r.put("error", t.toString());
                    }
                    final IWidget hovered = mui.getContext()
                        .getHovered();
                    r.put("hovered", hovered == null ? null : describe(hovered));
                    final List<String> below = new ArrayList<>();
                    for (final IWidget w : mui.getContext()
                        .getAllBelowMouse()) below.add(describe(w));
                    r.put("belowMouse", below);
                    final List<String> panelList = new ArrayList<>();
                    for (final ModularPanel p : mui.getPanelManager()
                        .getOpenPanels()) {
                        for (final com.cleanroommc.modularui.screen.viewport.LocatedWidget lw : p
                            .getAllHoveringList(false)) {
                            panelList.add(p.getName() + ": " + describe((IWidget) lw.getElement()));
                        }
                    }
                    r.put("panelHovering", panelList);
                    return r;
                });
            case "/board":
                return onClient(DevBoard::board);
            case "/view":
                return onClient(() -> {
                    DevBoard.view(
                        Float.parseFloat(q.getOrDefault("zoom", "1")),
                        Float.parseFloat(q.getOrDefault("panX", "0")),
                        Float.parseFloat(q.getOrDefault("panY", "0")));
                    return ok();
                });
            case "/sound":
                // The master volume, 0 to 1 (mc.sh starts the game at PLANNH_DEV_SOUND); no volume: just report it.
                return onClient(() -> {
                    if (q.containsKey("volume")) {
                        final float v = Math.max(0f, Math.min(1f, Float.parseFloat(q.get("volume"))));
                        mc.gameSettings.setSoundLevel(SoundCategory.MASTER, v);
                        mc.gameSettings.saveOptions();
                    }
                    final Map<String, Object> r = new LinkedHashMap<>();
                    r.put("volume", mc.gameSettings.getSoundLevel(SoundCategory.MASTER));
                    return r;
                });
            case "/library":
                // The site the library reads and posts to: url=<base> (tools/dev/mock-library.mjs for a local
                // stand-in).
                if (q.containsKey("url")) com.sbancuz.plannh.library.CommunityApi.useSite(q.get("url"));
                return onClient(() -> {
                    final Map<String, Object> r = new LinkedHashMap<>();
                    r.put("site", com.sbancuz.plannh.library.CommunityApi.site());
                    r.put("signedInAs", com.sbancuz.plannh.library.Account.username());
                    return r;
                });
            case "/nei":
                // NEI's recipes for item=<modid:name[:meta] or ore name>; uses=1 for its uses; planner=0 from the
                // inventory (the planner closed); tab=<part of a tab's name> to open on that tab.
                requireWorld();
                return onClient(
                    () -> DevRecipes.openNei(
                        arg(q, "item"),
                        "1".equals(q.get("uses")),
                        !"0".equals(q.get("planner")),
                        q.getOrDefault("tab", "")));
            case "/recipeinfo":
                requireWorld();
                return onClient(
                    () -> DevRecipes
                        .recipeInfo(arg(q, "output"), q.getOrDefault("handler", ""), q.getOrDefault("input", "")));
            case "/gtmachines":
                return onClient(() -> {
                    try {
                        return DevMachines
                            .list(q.getOrDefault("q", ""), "1".equals(q.get("all")), "1".equals(q.get("art")));
                    } catch (final LinkageError e) {
                        return error("GregTech is not loaded (start with PLANNH_GTNH=1)");
                    }
                });
            case "/slots":
                // Plan slots: list them; add=<name> opens a new one; switch=<i>; delete=<i>.
                requireWorld();
                return onClient(() -> {
                    final com.sbancuz.plannh.data.flowchart.Plan plan = com.sbancuz.plannh.data.flowchart.Plan
                        .getInstance();
                    com.sbancuz.plannh.data.flowchart.Plan.getActiveGraph();
                    if (q.containsKey("add")) {
                        plan.getGraphs()
                            .add(new com.sbancuz.plannh.data.flowchart.Graph(q.get("add")));
                        plan.setActiveIndex(
                            plan.getGraphs()
                                .size() - 1);
                    } else if (q.containsKey("switch")) {
                        final int i = intArg(q, "switch");
                        if (i >= 0 && i < plan.getGraphs()
                            .size()) plan.setActiveIndex(i);
                    } else if (q.containsKey("delete")) {
                        plan.removeSlot(intArg(q, "delete"));
                    }
                    com.sbancuz.plannh.api.PlanAPI.save();
                    final Map<String, Object> m = new LinkedHashMap<>();
                    final List<String> names = new ArrayList<>();
                    for (final com.sbancuz.plannh.data.flowchart.Graph g : plan.getGraphs()) names.add(g.getName());
                    m.put("slots", names);
                    m.put("active", plan.getActiveIndex());
                    return m;
                });
            case "/clearplan":
                requireWorld();
                return onClient(DevRecipes::clearPlan);
            case "/importff":
                // Imports a Factory Flow plan as a new slot: file=<path> (absolute, or from the repo root) or text=.
                requireWorld(); {
                final String text;
                if (q.containsKey("file")) {
                    java.io.File f = new java.io.File(q.get("file"));
                    if (!f.isAbsolute()) f = new java.io.File(new java.io.File(mc.mcDataDir, "../.."), q.get("file"));
                    text = java.nio.file.Files.readString(f.toPath());
                } else text = arg(q, "text");
                return onClient(() -> {
                    final com.sbancuz.plannh.importer.FfConverter.Result result;
                    try {
                        result = com.sbancuz.plannh.importer.game.FactoryFlowImport.importAsSlot(text);
                    } catch (final RuntimeException e) {
                        return error(String.valueOf(e.getMessage()));
                    }
                    final boolean wasOpen = com.sbancuz.plannh.ui.Planner.isPlanner(mc.currentScreen);
                    if (wasOpen) {
                        mc.displayGuiScreen(null);
                        openFlowchart();
                    }
                    final Map<String, Object> m = new LinkedHashMap<>();
                    m.put(
                        "name",
                        result.graph()
                            .getName());
                    m.put(
                        "summary",
                        result.report()
                            .summary());
                    final List<String> entries = new ArrayList<>();
                    for (final com.sbancuz.plannh.importer.ImportReport.Entry e : result.report()
                        .entries()) entries.add(e.toString());
                    m.put("report", entries);
                    return m;
                });
            }
            case "/iconatlas":
                // Writes the icon-shadow atlas to run/client/screenshots/<name, default icon-atlas.png>.
                return onFrame(() -> {
                    final java.awt.image.BufferedImage image = com.sbancuz.plannh.ui.theme.IconShadows.atlasImage();
                    if (image == null) return error("no atlas yet: nothing has asked for an icon shadow");
                    final java.io.File out = new java.io.File(
                        mc.mcDataDir,
                        "screenshots/" + q.getOrDefault("name", "icon-atlas.png"));
                    try {
                        javax.imageio.ImageIO.write(image, "png", out);
                    } catch (final java.io.IOException e) {
                        return error(e.toString());
                    }
                    final Map<String, Object> m = new LinkedHashMap<>();
                    m.put("file", out.getAbsolutePath());
                    m.put("cells", com.sbancuz.plannh.ui.theme.IconShadows.cells());
                    return m;
                });
            case "/structurepic":
                requireWorld();
                return onFrame(() -> structurePicture(q));
            case "/quit":
                frameActions.add(mc::shutdown);
                return ok();
            default:
                throw new IllegalArgumentException("unknown endpoint " + path + ", see /help");
        }
    }

    private static Map<String, Object> help() {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put(
            "endpoints",
            List.of(
                "/status - ready flag, current screen, display and GUI sizes, mouse position (GUI coords)",
                "/open - open the PlanNH flowchart; /close - close the current screen",
                "/screenshot?name=x.png[&x&y&w&h] - save the next frame (optionally a GUI-coord crop), returns the path",
                "/widgets - dump the ModularUI widget tree with GUI-coordinate areas",
                "/move?x&y, /click?x&y&button&count&mods, /drag?x1&y1&x2&y2&steps&button&mods, /scroll?x&y&amount - GUI coords",
                "/key?code[&char][&mods=ctrl,shift,alt] - LWJGL2 key code, /type?text - text into the focused field",
                "/cmd?c=/time set day - run a command as the player",
                "/addrecipe?output=dustRutile[&handler=blast][&input=ilmenite][&x&y] - put a real recipe on the board (with the board open: placed and auto-wired like NEI's +)",
                "/recipeinfo?output[&handler][&input] - what NEI and PlanNH see in a recipe (stacks, ports), read-only",
                "/nei?item[&uses=1][&planner=0][&tab] - open NEI's recipes for an item, over the planner or (planner=0) from the inventory",
                "/sound[?volume=0..1] - the master volume, set or read",
                "/library[?url=<base>] - the site the library uses (tools/dev/mock-library.mjs is a local stand-in)",
                "/gtmachines?q=turbine[&all=1][&art=1] - GregTech multiblocks (all=1: every machine) as the game names them; art=1 adds the bundled picture each resolves to",
                "/slots[?add=name | switch=i | delete=i] - list, open, switch or delete plan slots",
                "/clearplan - empty the active board (one undoable edit)",
                "/board - open board as data: view, and per card its state and every control's GUI rect (cx, cy)",
                "/view?zoom&panX&panY - set the board view (defaults 1, 0, 0)",
                "/structurepic?meta=1000 - (re)build that GT multiblock's card picture, save it as screenshots/structure-<meta>.png; no meta lists the controllers",
                "/quit - ask the client to quit (may hang on a confirm dialog with GT; mc.sh stop kills)"));
        return m;
    }

    // endregion

    // region Endpoint implementations

    private Map<String, Object> status() {
        final Map<String, Object> m = new LinkedHashMap<>();
        final ScaledResolution sr = scaled();
        m.put("ready", ready);
        m.put("inWorld", mc.theWorld != null);
        m.put(
            "screen",
            mc.currentScreen == null ? null
                : mc.currentScreen.getClass()
                    .getName());
        final ModularScreen mui = muiScreen();
        m.put(
            "muiScreen",
            mui == null ? null
                : mui.getClass()
                    .getName());
        if (mui != null) {
            final IWidget hovered = mui.getContext()
                .getHovered();
            final LocatedWidget focused = mui.getContext()
                .getFocusedWidget();
            m.put("hovered", hovered == null ? null : describe(hovered));
            m.put("focused", focused == null || focused.getElement() == null ? null : describe(focused.getElement()));
            final List<Object> panels = new ArrayList<>();
            for (final ModularPanel p : mui.getPanelManager()
                .getOpenPanels()) {
                panels.add(
                    p.getName() + " enabled="
                        + p.isEnabled()
                        + " anyHovered="
                        + p.isAnyHovered()
                        + " valid="
                        + p.isValid());
            }
            m.put("panels", panels);
            m.put(
                "muiMouseX",
                mui.getContext()
                    .getAbsMouseX());
            m.put(
                "muiMouseY",
                mui.getContext()
                    .getAbsMouseY());
        }
        m.put("windowActive", org.lwjgl.opengl.Display.isActive());
        // Seconds since the person last moved the mouse or pressed something in the game window.
        m.put("idleSeconds", (System.currentTimeMillis() - lastUserMs) / 1000);
        // Minecraft keeps "N fps, M chunk updates" in its debug string.
        m.put(
            "fps",
            Integer.parseInt(
                mc.debug.replaceFirst(" fps.*", "")
                    .trim()));
        m.put("displayWidth", mc.displayWidth);
        m.put("displayHeight", mc.displayHeight);
        m.put("guiWidth", sr.getScaledWidth());
        m.put("guiHeight", sr.getScaledHeight());
        m.put("guiScale", sr.getScaleFactor());
        m.put("mouseX", Mouse.getX() * sr.getScaledWidth() / mc.displayWidth);
        m.put("mouseY", sr.getScaledHeight() - Mouse.getY() * sr.getScaledHeight() / mc.displayHeight - 1);
        return m;
    }

    /** Full frame, or with x/y/w/h (GUI coordinates) just that region, at native resolution. */
    private Object screenshot(final String name, final Map<String, String> q) throws Exception {
        if (!name.matches("[A-Za-z0-9._-]+\\.png")) throw new IllegalArgumentException("name must be like foo.png");
        final boolean crop = q.containsKey("x");
        final int cx = crop ? intArg(q, "x") : 0, cy = crop ? intArg(q, "y") : 0;
        final int cw = crop ? intArg(q, "w") : 0, ch = crop ? intArg(q, "h") : 0;
        // Skip one frame so anything queued just before this request has been drawn.
        final CompletableFuture<Object> done = new CompletableFuture<>();
        frameActions.add(() -> frameActions.add(() -> {
            try {
                ScreenShotHelper
                    .saveScreenshot(mc.mcDataDir, name, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
                final File file = new File(new File(mc.mcDataDir, "screenshots"), name);
                if (crop) {
                    final int s = scaled().getScaleFactor();
                    final BufferedImage full = ImageIO.read(file);
                    final int x = Math.max(0, cx * s), y = Math.max(0, cy * s);
                    final int w = Math.min(full.getWidth() - x, cw * s), h = Math.min(full.getHeight() - y, ch * s);
                    ImageIO.write(full.getSubimage(x, y, w, h), "png", file);
                }
                final Map<String, Object> m = ok();
                m.put("path", file.getCanonicalPath());
                done.complete(m);
            } catch (final Throwable t) {
                done.completeExceptionally(t);
            }
        }));
        return done.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Rebuilds one multiblock's recipe-card picture (replacing the cached one, so an open board shows it) and saves its
     * pixels; without {@code meta}, lists the controllers that can have one.
     */
    private Map<String, Object> structurePicture(final Map<String, String> q) {
        if (!MultiblockPictures.available()) {
            return error("multiblock pictures need GregTech, BlockRenderer6343 and GL 3.0 (start with PLANNH_GTNH=1)");
        }
        if (!q.containsKey("meta")) {
            final Map<String, Object> m = ok();
            m.put("controllers", MultiblockPictures.controllers());
            return m;
        }
        final MultiblockPictures.Build build = MultiblockPictures.rebuild(intArg(q, "meta"));
        final Map<String, Object> m = ok();
        m.put("meta", build.meta());
        m.put("name", build.name());
        m.put("status", build.status());
        m.put("size", List.of(build.sizeX(), build.sizeY(), build.sizeZ()));
        m.put("blocks", build.blocks());
        m.put("buildMs", build.buildMillis());
        m.put("renderMs", build.renderMillis());
        if (build.argb() != null) {
            final int side = MultiblockPictures.SIZE;
            final BufferedImage image = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, side, side, build.argb(), 0, side);
            final File dir = new File(mc.mcDataDir, "screenshots");
            final File file = new File(dir, "structure-" + build.meta() + ".png");
            try {
                Files.createDirectories(dir.toPath());
                ImageIO.write(image, "png", file);
                m.put("path", file.getCanonicalPath());
            } catch (final IOException e) {
                m.put("pathError", e.toString());
            }
        }
        return m;
    }

    private Object widgets() {
        final ModularScreen screen = muiScreen();
        if (screen == null) throw new IllegalArgumentException("current screen is not a ModularUI screen");
        final Map<String, Object> main = dumpWidget(screen.getMainPanel(), 0);
        // Popups (menus, number boxes) are panels of their own, above the main one.
        final List<Object> popups = new ArrayList<>();
        for (final ModularPanel p : screen.getPanelManager()
            .getOpenPanels()) if (p != screen.getMainPanel()) popups.add(dumpWidget(p, 0));
        if (!popups.isEmpty()) main.put("popups", popups);
        return main;
    }

    private static String describe(final IWidget widget) {
        final Area a = widget.getArea();
        return widget.getClass()
            .getSimpleName() + " @"
            + a.x()
            + ","
            + a.y()
            + " "
            + a.w()
            + "x"
            + a.h();
    }

    private static Map<String, Object> dumpWidget(final IWidget widget, final int depth) {
        final Map<String, Object> m = new LinkedHashMap<>();
        final Area a = widget.getArea();
        m.put(
            "type",
            widget.getClass()
                .getSimpleName());
        if (widget.getName() != null) m.put("name", widget.getName());
        m.put("x", a.x());
        m.put("y", a.y());
        m.put("w", a.w());
        m.put("h", a.h());
        if (!widget.isEnabled()) m.put("enabled", false);
        final List<IWidget> children = widget.getChildren();
        if (!children.isEmpty() && depth < 32) {
            final List<Object> out = new ArrayList<>();
            for (final IWidget child : children) out.add(dumpWidget(child, depth + 1));
            m.put("children", out);
        }
        return m;
    }

    private void openFlowchart() {
        com.sbancuz.plannh.ui.Planner.open();
    }

    private Object click(final Map<String, String> q) throws Exception {
        final int x = intArg(q, "x"), y = intArg(q, "y"), button = intArg(q, "button", 0);
        final int count = Math.max(1, intArg(q, "count", 1));
        final List<Integer> mods = mods(q);
        final List<Runnable> actions = new ArrayList<>();
        for (final int m : mods) actions.add(() -> SyntheticInput.key(m, 0, true));
        actions.add(() -> moveTo(x, y));
        actions.add(() -> {});
        actions.add(() -> {});
        // Consecutive ticks are 50ms apart, well inside any double-click window.
        for (int i = 0; i < count; i++) {
            actions.add(() -> SyntheticInput.button(button, true));
            actions.add(() -> SyntheticInput.button(button, false));
        }
        for (final int m : mods) actions.add(() -> SyntheticInput.key(m, 0, false));
        return input(actions);
    }

    private Object drag(final Map<String, String> q) throws Exception {
        final int x1 = intArg(q, "x1"), y1 = intArg(q, "y1"), x2 = intArg(q, "x2"), y2 = intArg(q, "y2");
        final int steps = Math.max(1, intArg(q, "steps", 10)), button = intArg(q, "button", 0);
        final List<Integer> mods = mods(q);
        final List<Runnable> actions = new ArrayList<>();
        for (final int m : mods) actions.add(() -> SyntheticInput.key(m, 0, true));
        actions.add(() -> moveTo(x1, y1));
        // Two drawn frames before the press, so it lands on what is under the mouse now (after a hot-swap stall the
        // first frame can still show the old hover).
        actions.add(() -> {});
        actions.add(() -> {});
        actions.add(() -> SyntheticInput.button(button, true));
        for (int i = 1; i <= steps; i++) {
            final int sx = x1 + (x2 - x1) * i / steps, sy = y1 + (y2 - y1) * i / steps;
            actions.add(() -> moveTo(sx, sy));
        }
        actions.add(() -> SyntheticInput.button(button, false));
        for (final int m : mods) actions.add(() -> SyntheticInput.key(m, 0, false));
        return input(actions);
    }

    /** mods=ctrl,shift,alt: LWJGL left-hand key codes to hold around an input. */
    private static List<Integer> mods(final Map<String, String> q) {
        final List<Integer> mods = new ArrayList<>();
        for (final String m : q.getOrDefault("mods", "")
            .split(",")) {
            switch (m.trim()) {
                case "ctrl" -> mods.add(29);
                case "shift" -> mods.add(42);
                case "alt" -> mods.add(56);
                default -> {}
            }
        }
        return mods;
    }

    private Object scroll(final Map<String, String> q) throws Exception {
        final int x = intArg(q, "x"), y = intArg(q, "y"), amount = intArg(q, "amount", 1);
        return input(List.of(() -> moveTo(x, y), () -> SyntheticInput.wheel(amount)));
    }

    private Object key(final Map<String, String> q) throws Exception {
        final int code = intArg(q, "code");
        final String ch = q.getOrDefault("char", "");
        final int codepoint = ch.isEmpty() ? 0 : ch.codePointAt(0);
        final List<Integer> mods = mods(q);
        final List<Runnable> steps = new ArrayList<>();
        for (final int m : mods) steps.add(() -> SyntheticInput.key(m, 0, true));
        steps.add(() -> SyntheticInput.key(code, codepoint, true));
        // hold=ms keeps it down that long (a step a tick, 50 ms each), for keys read while held.
        for (int i = 0; i < intArg(q, "hold", 0) / 50; i++) {
            steps.add(SyntheticInput::reassertHeld);
        }
        steps.add(() -> SyntheticInput.key(code, codepoint, false));
        for (final int m : mods) steps.add(() -> SyntheticInput.key(m, 0, false));
        return input(steps);
    }

    private Object type(final Map<String, String> q) throws Exception {
        final String text = arg(q, "text");
        return input(List.of(() -> SyntheticInput.text(text)));
    }

    /** Converts GUI coordinates to window pixels and injects a motion event there. */
    private void moveTo(final int guiX, final int guiY) {
        final int scale = scaled().getScaleFactor();
        // Aim at the middle of the GUI pixel so integer division in the game lands back on guiX/guiY.
        SyntheticInput.moveTo(guiX * scale + scale / 2, guiY * scale + scale / 2);
    }

    // endregion

    // region Helpers

    private Object input(final List<Runnable> steps) throws Exception {
        if (!SyntheticInput.available()) {
            throw new IllegalStateException("synthetic input needs lwjgl3ify: launch with runClient25");
        }
        final CompletableFuture<Object> done = new CompletableFuture<>();
        for (final Runnable step : steps) tickActions.add(() -> {
            try {
                lastSyntheticMs = System.currentTimeMillis();
                step.run();
            } catch (final Throwable t) {
                done.completeExceptionally(t);
            }
        });
        tickActions.add(() -> done.complete(ok()));
        return done.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private <T> T onClient(final Supplier<T> task) throws Exception {
        final CompletableFuture<T> done = new CompletableFuture<>();
        tickActions.add(() -> {
            try {
                done.complete(task.get());
            } catch (final Throwable t) {
                done.completeExceptionally(t);
            }
        });
        return done.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** Like {@link #onClient}, but at the end of a rendered frame (for offscreen GL work). */
    private <T> T onFrame(final Supplier<T> task) throws Exception {
        final CompletableFuture<T> done = new CompletableFuture<>();
        frameActions.add(() -> {
            try {
                done.complete(task.get());
            } catch (final Throwable t) {
                done.completeExceptionally(t);
            }
        });
        return done.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void requireWorld() {
        if (mc.thePlayer == null) throw new IllegalArgumentException("not in a world yet, poll /status for ready");
    }

    private ModularScreen muiScreen() {
        final GuiScreen screen = mc.currentScreen;
        return screen instanceof IMuiScreen muiScreen ? muiScreen.getScreen() : null;
    }

    private ScaledResolution scaled() {
        return new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
    }

    private static Map<String, String> query(final HttpExchange exchange) throws IOException {
        final Map<String, String> out = new LinkedHashMap<>();
        final String raw = exchange.getRequestURI()
            .getRawQuery();
        if (raw == null) return out;
        for (final String part : raw.split("&")) {
            final int eq = part.indexOf('=');
            final String k = eq < 0 ? part : part.substring(0, eq);
            final String v = eq < 0 ? "" : part.substring(eq + 1);
            out.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
        }
        return out;
    }

    private static String arg(final Map<String, String> q, final String name) {
        final String v = q.get(name);
        if (v == null) throw new IllegalArgumentException("missing parameter '" + name + "'");
        return v;
    }

    private static int intArg(final Map<String, String> q, final String name) {
        try {
            return Integer.parseInt(arg(q, name));
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("parameter '" + name + "' must be an integer");
        }
    }

    private static int intArg(final Map<String, String> q, final String name, final int fallback) {
        return q.containsKey(name) ? intArg(q, name) : fallback;
    }

    private static Map<String, Object> ok() {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }

    private static Map<String, Object> error(final String message) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", message);
        return m;
    }

    // endregion

    /**
     * Feeds events into lwjgl3ify's emulated LWJGL2 input queues, the same path real SDL input takes, so the game
     * cannot tell them apart and the user's real cursor is never touched. Reflection only: lwjgl3ify and LWJGL's SDL
     * bindings exist on the Java 17+ run classpath, not the compile classpath.
     */
    private static final class SyntheticInput {

        private static boolean resolved;
        private static Class<?> motionEventClass;
        private static Method addMoveEvent, addButtonEvent, addWheelEvent, lwjglToSdlButton, pixelScale;
        private static Method addRawKeyEvent, addCharEvent, injectTextEvent;
        private static java.lang.reflect.Constructor<?> keyEventCtor, textEventCtor;
        private static Object keyPress, keyRelease;
        private static Field buttonFlags;
        private static int lastX, lastY;

        static synchronized boolean available() {
            if (!resolved) {
                resolved = true;
                try {
                    final Class<?> mouse = Class.forName("org.lwjglx.input.Mouse");
                    final Class<?> keyboard = Class.forName("org.lwjglx.input.Keyboard");
                    final Class<?> keyEvent = Class.forName("org.lwjglx.input.Keyboard$KeyEvent");
                    final Class<?> keyState = Class.forName("org.lwjglx.input.Keyboard$KeyState");
                    motionEventClass = Class.forName("org.lwjgl.sdl.SDL_MouseMotionEvent");
                    addMoveEvent = mouse.getMethod("addMoveEvent", motionEventClass);
                    addButtonEvent = mouse.getMethod("addButtonEvent", int.class, boolean.class);
                    addWheelEvent = mouse.getMethod("addWheelEvent", double.class);
                    lwjglToSdlButton = mouse.getMethod("lwjglToSdlMouseButton", int.class);
                    buttonFlags = mouse.getField("sdlMouseButtonFlags");
                    pixelScale = Class.forName("org.lwjglx.opengl.Display")
                        .getMethod("getPixelScaleFactor");
                    addRawKeyEvent = keyboard.getMethod("addRawKeyEvent", keyEvent);
                    final Class<?> inputEvents = Class.forName("me.eigenraven.lwjgl3ify.api.InputEvents");
                    final Class<?> textEvent = Class.forName("me.eigenraven.lwjgl3ify.api.InputEvents$TextEvent");
                    injectTextEvent = inputEvents.getMethod("injectTextEvent", textEvent);
                    addCharEvent = keyboard.getMethod("addCharEvent", int.class, int.class);
                    textEventCtor = textEvent.getConstructor(String.class);
                    keyEventCtor = keyEvent.getConstructor(int.class, int.class, int.class, keyState, long.class);
                    keyPress = keyState.getField("PRESS")
                        .get(null);
                    keyRelease = keyState.getField("RELEASE")
                        .get(null);
                } catch (final ReflectiveOperationException e) {
                    PlanNH.LOG.warn("[dev] Synthetic input unavailable: {}", e.toString());
                    addMoveEvent = null;
                }
            }
            return addMoveEvent != null;
        }

        /** @param px window pixels from the left, @param py window pixels from the top */
        static void moveTo(final int px, final int py) {
            try {
                final float scale = (float) pixelScale.invoke(null);
                final Object event = motionEventClass.getMethod("calloc")
                    .invoke(null);
                motionEventClass.getMethod("x", float.class)
                    .invoke(event, px / scale);
                motionEventClass.getMethod("y", float.class)
                    .invoke(event, py / scale);
                // Relative to where the cursor really is: the game recentres it when a screen opens, behind our back.
                final int curX = org.lwjgl.input.Mouse.getX();
                final int curY = Minecraft.getMinecraft().displayHeight - 1 - org.lwjgl.input.Mouse.getY();
                motionEventClass.getMethod("xrel", float.class)
                    .invoke(event, (px - curX) / scale);
                motionEventClass.getMethod("yrel", float.class)
                    .invoke(event, (py - curY) / scale);
                addMoveEvent.invoke(null, event);
                motionEventClass.getMethod("free")
                    .invoke(event);
                lastX = px;
                lastY = py;
                reassertHeld();
            } catch (final ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        static void button(final int button, final boolean down) {
            try {
                // isButtonDown() reads the SDL button mask rather than the event queue, so keep both in step.
                final int sdl = (byte) lwjglToSdlButton.invoke(null, button);
                final int mask = 1 << (sdl - 1);
                final int flags = buttonFlags.getInt(null);
                buttonFlags.setInt(null, down ? flags | mask : flags & ~mask);
                addButtonEvent.invoke(null, button, down);
                reassertHeld();
            } catch (final ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        static void wheel(final int amount) {
            try {
                addWheelEvent.invoke(null, (double) amount);
            } catch (final ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        /** SDL scancodes of synthetic keys pressed and not yet released. */
        private static final java.util.Set<Integer> HELD = new java.util.HashSet<>();

        static void key(final int code, final int codepoint, final boolean down) {
            try {
                addRawKeyEvent.invoke(
                    null,
                    keyEventCtor.newInstance(code, code, codepoint, down ? keyPress : keyRelease, System.nanoTime()));
                // isKeyDown() (Ctrl and Shift checks) reads SDL's key state array, not the event queue: keep it in
                // step.
                final Class<?> keyboard = Class.forName("org.lwjglx.input.Keyboard");
                final java.lang.reflect.Field state = keyboard.getDeclaredField("sdlKeyPressedArray");
                state.setAccessible(true);
                final java.nio.ByteBuffer pressed = (java.nio.ByteBuffer) state.get(null);
                final int scancode = (int) Class.forName("org.lwjglx.input.KeyCodes")
                    .getMethod("lwjglToSdlScancode", int.class)
                    .invoke(null, code);
                if (down) HELD.add(scancode);
                else HELD.remove(scancode);
                if (pressed != null && scancode > 0 && scancode < pressed.limit())
                    pressed.put(scancode, (byte) (down ? 1 : 0));
                reassertHeld();
            } catch (final ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        /** SDL refreshes its key state array as it pumps events, so re-assert every key still held (modifiers). */
        static void reassertHeld() {
            if (HELD.isEmpty()) return;
            try {
                final java.lang.reflect.Field state = Class.forName("org.lwjglx.input.Keyboard")
                    .getDeclaredField("sdlKeyPressedArray");
                state.setAccessible(true);
                final java.nio.ByteBuffer pressed = (java.nio.ByteBuffer) state.get(null);
                if (pressed != null)
                    for (final int held : HELD) if (held > 0 && held < pressed.limit()) pressed.put(held, (byte) 1);
            } catch (final ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        /**
         * Mirrors Lwjgl3ifyEventLoop#handleTextEvent: an lwjgl3ify text event (vanilla text fields listen for these)
         * followed by one LWJGL2 char event per character (GuiScreen#keyTyped and ModularUI read those).
         */
        static void text(final String text) {
            try {
                injectTextEvent.invoke(null, textEventCtor.newInstance(text));
                for (final int c : text.chars()
                    .toArray()) addCharEvent.invoke(null, c, c);
            } catch (final ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}

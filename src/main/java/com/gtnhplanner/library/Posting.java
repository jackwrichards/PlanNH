package com.gtnhplanner.library;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.api.PlanAPI;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Plan;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.importer.game.FactoryFlowImport;
import com.gtnhplanner.ui.Resources;
import com.gtnhplanner.ui.card.CardDefaults;
import com.gtnhplanner.ui.card.CardModel;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;

/**
 * Signing in to gtnhplanner.com, and posting, changing and taking down the player's plans there, from the game: the
 * calls run in the background and answer on the client thread. The plan goes as Factory Flow's project JSON
 * ({@link PlanExport}). A plan remembers the post it went up as ({@link Graph#getPostId()}), so posting it again
 * updates that post, keeping its votes, downloads and comments.
 */
public final class Posting {

    private static final ExecutorService NET = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "GTNH Planner account");
        t.setDaemon(true);
        return t;
    });

    private Posting() {}

    /** Signs in, or makes the account and signs in ({@code create}); {@code done} gets the name. */
    public static void signIn(final String username, final String password, final boolean create,
        final Consumer<String> done, final Consumer<String> failed) {
        NET.execute(() -> {
            try {
                final CommunityApi.SignedIn s = create ? CommunityApi.register(username, password)
                    : CommunityApi.signIn(username, password);
                onClient(() -> {
                    Account.signedIn(s);
                    done.accept(s.username());
                });
            } catch (final Exception e) {
                onClient(() -> failed.accept(reason(e)));
            }
        });
    }

    /** A plan as the site's project JSON, as {@link #post} sends it (the dev harness writes it to a file). */
    public static JsonObject write(final Graph g, final String title, final Function<UUID, Double> machines) {
        return PlanExport.project(g, title, new GameWorld(machines));
    }

    /** What a post says about itself: its title, a line about it, and its icon (null for none). */
    public record Face(String title, String description, @Nullable CommunityApi.Resource icon) {}

    /**
     * Posts a plan, or updates the post it went up as when it has one ({@code update}); {@code done} runs when it
     * is up. The plan keeps the face it went out with, and the post's id. {@code machines} is how many machines the
     * board runs each card at. An update of a post that is gone (or not this player's) forgets it and says so; posting
     * again then makes a new one.
     */
    public static void post(final Graph g, final Face face, final boolean update, final Function<UUID, Double> machines,
        final Runnable done, final Consumer<String> failed) {
        final String token = Account.token();
        if (token == null) {
            failed.accept("Sign in first");
            return;
        }
        final String postId = update ? g.getPostId() : null;
        // Read the plan here, on the client thread, where the game's items and NEI's recipes are safe to touch. The
        // plan carries its face too, as the website's plans do.
        final String oldDescription = g.getDescription();
        final String oldIcon = g.getIcon();
        final JsonObject plan;
        try {
            g.setDescription(face.description());
            g.setIcon(keyOf(face.icon()));
            plan = PlanExport.project(g, face.title(), new GameWorld(machines));
        } catch (final RuntimeException e) {
            GtnhPlanner.LOG.warn("Could not write the plan out for posting", e);
            failed.accept("Couldn't write the plan out: " + reason(e));
            return;
        } finally {
            g.setDescription(oldDescription);
            g.setIcon(oldIcon);
        }
        final String version = packVersion();
        NET.execute(() -> {
            try {
                final JsonObject icon = siteIcon(face.icon(), version);
                if (icon != null) plan.add("icon", icon);
                final String id;
                if (postId != null) {
                    final JsonObject fields = faceFields(face, icon);
                    fields.addProperty("gameVersion", version);
                    fields.add("plan", plan);
                    CommunityApi.update(token, postId, fields);
                    id = postId;
                } else id = CommunityApi.post(
                    token,
                    new CommunityApi.Post(face.title(), face.description(), icon, version, Account.deviceId(), plan));
                onClient(() -> {
                    g.setPostId(id);
                    g.setDescription(face.description());
                    g.setIcon(keyOf(face.icon()));
                    PlanAPI.save();
                    done.run();
                });
            } catch (final Exception e) {
                GtnhPlanner.LOG.info("Library: could not post", e);
                onClient(() -> {
                    if (e instanceof final CommunityApi.Refused r && r.postGone()) {
                        unlink(postId);
                        failed.accept(
                            r.status == 403 ? "That post is another account's" : "That post is gone from the library");
                    } else failed.accept(refused(e));
                });
            }
        });
    }

    /**
     * Changes a post's title, description and icon, its plan left as it is; {@code done} gets the setup as it is now.
     * The player's plans that went up as it take the new description and icon.
     */
    public static void edit(final CommunityApi.Setup s, final Face face, final Consumer<CommunityApi.Setup> done,
        final Consumer<String> failed) {
        final String token = Account.token();
        if (token == null) {
            failed.accept("Sign in first");
            return;
        }
        final String version = packVersion();
        NET.execute(() -> {
            try {
                CommunityApi.update(token, s.id(), faceFields(face, siteIcon(face.icon(), version)));
                onClient(() -> {
                    for (final Graph g : linked(s.id())) {
                        g.setDescription(face.description());
                        g.setIcon(keyOf(face.icon()));
                    }
                    PlanAPI.save();
                    done.accept(s.withFace(face.title(), face.description(), face.icon()));
                });
            } catch (final Exception e) {
                GtnhPlanner.LOG.info("Library: could not edit {}", s.id(), e);
                onClient(() -> failed.accept(refused(e)));
            }
        });
    }

    /** Takes a post down for good; the player's plans that went up as it forget it (they are kept). */
    public static void delete(final CommunityApi.Setup s, final Runnable done, final Consumer<String> failed) {
        final String token = Account.token();
        if (token == null) {
            failed.accept("Sign in first");
            return;
        }
        NET.execute(() -> {
            try {
                CommunityApi.delete(token, s.id());
                onClient(() -> {
                    unlink(s.id());
                    done.run();
                });
            } catch (final Exception e) {
                GtnhPlanner.LOG.info("Library: could not delete {}", s.id(), e);
                onClient(() -> {
                    if (e instanceof final CommunityApi.Refused r && r.status == 404) {
                        // Already gone: what was asked for.
                        unlink(s.id());
                        done.run();
                    } else failed.accept(refused(e));
                });
            }
        });
    }

    /** The fields an edit sends: title, description, and the icon (null clears it). */
    private static JsonObject faceFields(final Face face, @Nullable final JsonObject icon) {
        final JsonObject fields = new JsonObject();
        fields.addProperty("name", face.title());
        fields.addProperty("description", face.description());
        fields.add("icon", icon == null ? JsonNull.INSTANCE : icon);
        return fields;
    }

    /** The icon as the site keeps it, with the website's picture when it has one. Off the client thread. */
    @Nullable
    private static JsonObject siteIcon(@Nullable final CommunityApi.Resource r, final String version) {
        return r == null ? null : CommunityApi.icon(r.kind(), r.id(), r.name(), version);
    }

    /** The player's plans that went up as a post. */
    private static java.util.List<Graph> linked(@Nullable final String postId) {
        final java.util.List<Graph> out = new java.util.ArrayList<>();
        if (postId == null) return out;
        for (final Graph g : Plan.getInstance()
            .getGraphs()) if (postId.equals(g.getPostId())) out.add(g);
        return out;
    }

    /** The post is gone: the plans that went up as it forget it, and are posted anew next time. */
    private static void unlink(@Nullable final String postId) {
        final java.util.List<Graph> plans = linked(postId);
        for (final Graph g : plans) g.setPostId(null);
        if (!plans.isEmpty()) PlanAPI.save();
    }

    /** A post's face from something on the board, by its resource key; null when the site could not name it. */
    @Nullable
    public static CommunityApi.Resource face(@Nullable final String key) {
        if (key == null || key.isEmpty()) return null;
        final PlanExport.Res r = new GameWorld(id -> 0d).resource(key);
        return r == null ? null : new CommunityApi.Resource(r.kind(), r.id(), r.name(), 0);
    }

    /** The board's resource key for a post's face; null when this game lacks it. */
    @Nullable
    public static String keyOf(@Nullable final CommunityApi.Resource face) {
        if (face == null) return null;
        if (face.fluid()) {
            final FluidStack f = FactoryFlowImport.fluid(face.id());
            return f == null || f.getFluid() == null ? null
                : "fluid:" + f.getFluid()
                    .getName();
        }
        final ItemStack i = FactoryFlowImport.item(face.id());
        return i == null || i.getItem() == null ? null : CardDefaults.itemKey(i);
    }

    /** The pack's version, as the site groups setups by it: the GTNH core mod's. */
    static String packVersion() {
        final ModContainer core = Loader.instance()
            .getIndexedModList()
            .get("dreamcraft");
        return core == null ? "" : core.getVersion();
    }

    /**
     * Why a call made signed in failed. A session the library no longer knows (it ran out) signs the player out here
     * too, so the next try asks them to sign in. Client thread.
     */
    private static String refused(final Exception e) {
        if (e instanceof final CommunityApi.Refused r && r.status == 401) {
            Account.signOut();
            return "Your sign-in ran out: sign in again";
        }
        return reason(e);
    }

    private static String reason(final Exception e) {
        return e.getMessage() == null ? e.getClass()
            .getSimpleName() : e.getMessage();
    }

    private static void onClient(final Runnable r) {
        Minecraft.getMinecraft()
            .func_152344_a(r);
    }

    /** The game's side of the export: Factory Flow's ids for its items and fluids, and the board's numbers. */
    private record GameWorld(Function<UUID, Double> counts) implements PlanExport.World {

        @Override
        @Nullable
        public PlanExport.Res port(final Port<?> port) {
            final Object v = port.getValue();
            if (v instanceof final ItemStack item && item.getItem() != null) {
                return new PlanExport.Res("item", FactoryFlowImport.idOf(item), item.getDisplayName());
            }
            if (v instanceof final FluidStack fluid && fluid.getFluid() != null) {
                return new PlanExport.Res("fluid", FactoryFlowImport.idOf(fluid), fluid.getLocalizedName());
            }
            return null;
        }

        @Override
        @Nullable
        public PlanExport.Res resource(final String key) {
            if (Resources.isFluid(key)) {
                final FluidStack f = Resources.fluid(key);
                return f == null ? null : new PlanExport.Res("fluid", FactoryFlowImport.idOf(f), f.getLocalizedName());
            }
            final ItemStack i = Resources.item(key);
            return i == null ? null : new PlanExport.Res("item", FactoryFlowImport.idOf(i), i.getDisplayName());
        }

        @Override
        public String machine(final Node node) {
            for (final ItemStack s : CardModel.catalystsOf(node)) {
                if (CardDefaults.matches(s, node.machineName)) return s.getDisplayName();
            }
            final java.util.List<ItemStack> all = CardModel.catalystsOf(node);
            return !all.isEmpty() ? all.get(0)
                .getDisplayName() : node.machineName;
        }

        @Override
        public double machines(final Node node) {
            final Double d = counts.apply(node.id);
            return d == null ? 0 : d;
        }

        @Override
        public String tier(final Node node) {
            final Object v = CardDefaults.setting(node.machineConfig, "voltage");
            final String t = v == null ? "" : v.toString();
            return t.isEmpty() || "OFF".equals(t) ? "LV" : t;
        }

        @Override
        public boolean multiblock(final Node node) {
            final com.gtnhplanner.machines.web.NodeMath.Result r = com.gtnhplanner.machines.game.WebEffect
                .result(node, node.machineConfig);
            return r != null ? com.gtnhplanner.machines.web.Power.isMultiblock(r.effectiveRecipe())
                : PlanExport.World.super.multiblock(node);
        }

        @Override
        @Nullable
        public String handlerId(final Node node) {
            final com.gtnhplanner.machines.web.Web.Recipe recipe = com.gtnhplanner.machines.game.WebEffect.recipe(node);
            return recipe == null ? null : com.gtnhplanner.machines.game.WebCards.handlerId(node, recipe);
        }
    }
}

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

import com.google.gson.JsonObject;
import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.importer.game.FactoryFlowImport;
import com.gtnhplanner.ui.Resources;
import com.gtnhplanner.ui.card.CardDefaults;
import com.gtnhplanner.ui.card.CardModel;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;

/**
 * Signing in to gtnhplanner.com and posting a plan to its public setups, from the game: the calls run in the
 * background and answer on the client thread. The plan goes as Factory Flow's project JSON ({@link PlanExport}).
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

    /**
     * Posts a plan; {@code done} gets its link. {@code machines} is how many machines the board runs each card at.
     */
    public static void post(final Graph g, final String title, final String description,
        final Function<UUID, Double> machines, final Consumer<String> done, final Consumer<String> failed) {
        final String token = Account.token();
        if (token == null) {
            failed.accept("Sign in first");
            return;
        }
        // Read the plan here, on the client thread, where the game's items and NEI's recipes are safe to touch.
        final JsonObject plan;
        try {
            plan = PlanExport.project(g, title, new GameWorld(machines));
        } catch (final RuntimeException e) {
            GtnhPlanner.LOG.warn("Could not write the plan out for posting", e);
            failed.accept("Couldn't write the plan out: " + reason(e));
            return;
        }
        final CommunityApi.Post p = new CommunityApi.Post(title, description, packVersion(), Account.deviceId(), plan);
        NET.execute(() -> {
            try {
                final String id = CommunityApi.post(token, p);
                onClient(() -> done.accept(CommunityApi.site() + "/?plan=" + id));
            } catch (final Exception e) {
                GtnhPlanner.LOG.info("Library: could not post", e);
                onClient(() -> failed.accept(reason(e)));
            }
        });
    }

    /** The pack's version, as the site groups setups by it: the GTNH core mod's. */
    static String packVersion() {
        final ModContainer core = Loader.instance()
            .getIndexedModList()
            .get("dreamcraft");
        return core == null ? "" : core.getVersion();
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
    }
}

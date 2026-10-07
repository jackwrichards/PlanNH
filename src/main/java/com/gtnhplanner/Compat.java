package com.gtnhplanner;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.gtnhplanner.api.RecipePropertyAPI;
import com.gtnhplanner.data.MachineProfileRegistry;
import com.gtnhplanner.data.properties.PropertyProvider;
import com.gtnhplanner.data.provider.AE2Provider;
import com.gtnhplanner.data.provider.AvaritiaProvider;
import com.gtnhplanner.data.provider.BinnieProvider;
import com.gtnhplanner.data.provider.BloodMagicProvider;
import com.gtnhplanner.data.provider.BotaniaProvider;
import com.gtnhplanner.data.provider.DefaultProvider;
import com.gtnhplanner.data.provider.DraconicEvolutionProvider;
import com.gtnhplanner.data.provider.EFRProvider;
import com.gtnhplanner.data.provider.EnderIOProvider;
import com.gtnhplanner.data.provider.ForestryProvider;
import com.gtnhplanner.data.provider.GTProvider;
import com.gtnhplanner.data.provider.GalacticraftProvider;
import com.gtnhplanner.data.provider.GendustryProvider;
import com.gtnhplanner.data.provider.HarvestCraftProvider;
import com.gtnhplanner.data.provider.LogisticsPipesProvider;
import com.gtnhplanner.data.provider.ProjectBlueProvider;
import com.gtnhplanner.data.provider.RailcraftProvider;
import com.gtnhplanner.data.provider.RandomThingsProvider;
import com.gtnhplanner.data.provider.ThaumcraftProvider;
import com.gtnhplanner.data.provider.ThaumicExplorationProvider;
import com.gtnhplanner.data.provider.ThaumicTinkererProvider;
import com.gtnhplanner.data.provider.TinkersConstructProvider;
import com.gtnhplanner.data.provider.VanillaProvider;
import com.gtnhplanner.data.provider.WitchingGadgetsProvider;

import cpw.mods.fml.common.Loader;
import lombok.Getter;

public enum Compat {

    AVARITIA(IDs.AVARITIA, AvaritiaProvider.class),
    APPLIEDENERGISTICS2(IDs.AE2, AE2Provider.class),
    BINNIE(IDs.BINNIE, BinnieProvider.class),
    BLOODMAGIC(IDs.BLOODMAGIC, BloodMagicProvider.class),
    BOTANIA(IDs.BOTANIA, BotaniaProvider.class),
    DRACONICEVOLUTION(IDs.DRACONICEVOLUTION, DraconicEvolutionProvider.class),
    ENDERIO(IDs.ENDERIO, EnderIOProvider.class),
    FORESTRY(IDs.FORESTRY, ForestryProvider.class),
    GALACTICRAFT(IDs.GALACTICRAFT, GalacticraftProvider.class),
    GENDUSTRY(IDs.GENDUSTRY, GendustryProvider.class),
    GREGTECH(IDs.GREGTECH, GTProvider.class),
    HARVESTCRAFT(IDs.HARVESTCRAFT, HarvestCraftProvider.class),
    LOGISTICSPIPES(IDs.LOGISTICSPIPES, LogisticsPipesProvider.class),
    PROJECTBLUE(IDs.PROJECTBLUE, ProjectBlueProvider.class),
    RAILCRAFT(IDs.RAILCRAFT, RailcraftProvider.class),
    RANDOMTHINGS(IDs.RANDOMTHINGS, RandomThingsProvider.class),
    THAUMCRAFT(IDs.THAUMCRAFT, ThaumcraftProvider.class),
    THAUMICEXPLORATION(IDs.THAUMICEXPLORATION, ThaumicExplorationProvider.class),
    THAUMICTINKERER(IDs.THAUMICTINKERER, ThaumicTinkererProvider.class),
    TINKERSCONSTRUCT(IDs.TINKERSCONSTRUCT, TinkersConstructProvider.class),
    WITCHINGGADGETS(IDs.WITCHINGGADGETS, WitchingGadgetsProvider.class),
    EFR(IDs.EFR, EFRProvider.class);

    public static class IDs {

        public static final String AVARITIA = "Avaritia";
        public static final String AE2 = "appliedenergistics2";
        public static final String BINNIE = "Binnie";
        public static final String BLOODMAGIC = "AWWayofTime";
        public static final String BOTANIA = "Botania";
        public static final String DRACONICEVOLUTION = "DraconicEvolution";
        public static final String ENDERIO = "EnderIO";
        public static final String FORESTRY = "Forestry";
        public static final String GALACTICRAFT = "Galacticraft";
        public static final String GENDUSTRY = "gendustry";
        public static final String GREGTECH = "gregtech";
        public static final String HARVESTCRAFT = "harvestcraft";
        public static final String LOGISTICSPIPES = "LogisticsPipes";
        public static final String PROJECTBLUE = "ProjectBlue";
        public static final String RAILCRAFT = "Railcraft";
        public static final String RANDOMTHINGS = "RandomThings";
        public static final String THAUMCRAFT = "Thaumcraft";
        public static final String THAUMICEXPLORATION = "ThaumicExploration";
        public static final String THAUMICTINKERER = "ThaumicTinkerer";
        public static final String TINKERSCONSTRUCT = "TConstruct";
        public static final String WITCHINGGADGETS = "WitchingGadgets";
        public static final String EFR = "etfuturum";
    }

    public final String modid;
    public final boolean isLoaded;

    @Nullable
    @Getter
    private PropertyProvider extractor;
    @Nonnull
    private final Class<? extends PropertyProvider> providerFactory;

    Compat(final String modid, final Class<? extends PropertyProvider> providerFactory) {
        this.modid = modid;
        this.isLoaded = Loader.isModLoaded(modid);
        this.providerFactory = providerFactory;
    }

    @Nullable
    private static <T extends PropertyProvider> PropertyProvider create(@Nonnull final Class<T> clazz) {
        try {
            return clazz.getDeclaredConstructor()
                .newInstance();
        } catch (Exception | NoClassDefFoundError _) {}
        return null;
    }

    public static void init() {
        MachineProfileRegistry.reset();
        RecipePropertyAPI.reset();
        DefaultProvider.INSTANCE.register();
        new VanillaProvider().register();
        for (final Compat mod : values()) {
            if (mod.isLoaded) {
                mod.extractor = create(mod.providerFactory);
                if (mod.extractor != null) {
                    mod.extractor.register();
                }
            }
        }
    }

}

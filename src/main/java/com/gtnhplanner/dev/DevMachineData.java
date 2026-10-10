package com.gtnhplanner.dev;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import bartworks.API.recipe.BartWorksRecipeMaps;
import gregtech.api.recipe.RecipeMaps;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.GTRecipeConstants;
import gregtech.api.util.recipe.Sievert;
import gregtech.common.tileentities.machines.multi.MTETreeFarm;

/**
 * The facts the website's dataset lacks for the Tree Growth Simulator and the Bacterial Vat, read from this game and
 * written as the website's side files (ids as its dataset writes them: the registry name in lower case, "@meta" when
 * the meta is not 0; fluids by name). Never hand-edit the files: run {@code call 'machinedata?dir=<path>'} in the
 * full pack. Client thread only; needs GregTech (bartworks inside it) and, for Forestry trees, Forestry.
 */
final class DevMachineData {

    private DevMachineData() {}

    static Map<String, Object> export(final File dir) throws IOException {
        Files.createDirectories(dir.toPath());
        final JsonObject trees = trees(), vat = vat(), radio = radioHatch();
        write(new File(dir, "tgs-trees.json"), trees);
        write(new File(dir, "bio-vat.json"), vat);
        write(new File(dir, "radio-hatch.json"), radio);
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("dir", dir.getAbsolutePath());
        m.put(
            "trees",
            trees.getAsJsonArray("trees")
                .size());
        m.put(
            "vatRecipes",
            vat.getAsJsonArray("recipes")
                .size());
        m.put(
            "radioMaterials",
            radio.getAsJsonArray("materials")
                .size());
        return m;
    }

    private static void write(final File f, final JsonObject o) throws IOException {
        Files.write(
            f.toPath(),
            new GsonBuilder().setPrettyPrinting()
                .disableHtmlEscaping()
                .create()
                .toJson(o)
                .getBytes(StandardCharsets.UTF_8));
    }

    // region Tree Growth Simulator

    private static final String[] MODES = { "log", "sapling", "leaves", "fruit" };

    /** Every tree NEI shows, in its order: the sapling, each mode's output as NEI shows it, and Forestry's genes. */
    private static JsonObject trees() {
        final JsonArray list = new JsonArray();
        for (final GTRecipe r : RecipeMaps.treeGrowthSimulatorFakeRecipes.getAllRecipes()) {
            if (!(r.mSpecialItems instanceof ItemStack sapling)) continue;
            final JsonObject t = new JsonObject();
            t.add("sapling", item(sapling, 1));
            final JsonObject outputs = new JsonObject();
            for (int mode = 0; mode < MODES.length; mode++) {
                final ItemStack out = r.mOutputs != null && mode < r.mOutputs.length ? r.mOutputs[mode] : null;
                if (out != null) outputs.add(MODES[mode], item(out, out.stackSize));
            }
            t.add("outputs", outputs);
            final JsonObject forestry = forestry(sapling);
            t.add("forestry", forestry == null ? JsonNull.INSTANCE : forestry);
            list.add(t);
        }
        final JsonObject o = new JsonObject();
        o.addProperty(
            "source",
            "MTETreeFarm's NEI recipes (gt.recipe.treefarm): outputs are NEI's, the product x the mode's multiplier");
        o.add("trees", list);
        return o;
    }

    /**
     * A Forestry sapling's species, its default genes, and its products before the genes scale them (the counts its
     * real outputs are worked from); null for any other sapling. Read by reflection: Forestry is not compiled against.
     */
    private static JsonObject forestry(final ItemStack sapling) {
        final String name = Item.itemRegistry.getNameForObject(sapling.getItem());
        if (!"Forestry:sapling".equals(name)) return null;
        try {
            final Object root = Class.forName("forestry.api.arboriculture.TreeManager")
                .getField("treeRoot")
                .get(null);
            final Object tree = root.getClass()
                .getMethod("getMember", ItemStack.class)
                .invoke(root, sapling);
            if (tree == null) return null;
            final Object genome = tree.getClass()
                .getMethod("getGenome")
                .invoke(tree);
            final String species = (String) tree.getClass()
                .getMethod("getIdent")
                .invoke(tree);
            final JsonObject f = new JsonObject();
            f.addProperty("species", species);
            f.addProperty("height", (Number) call(genome, "getHeight"));
            f.addProperty("girth", (Number) call(genome, "getGirth"));
            f.addProperty("fertility", (Number) call(genome, "getFertility"));
            f.addProperty("yield", (Number) call(genome, "getYield"));
            final EnumMap<MTETreeFarm.Mode, ItemStack> base = MTETreeFarm.treeProductsMap
                .get("Forestry:sapling:" + species);
            final JsonObject counts = new JsonObject();
            if (base != null) for (final Map.Entry<MTETreeFarm.Mode, ItemStack> e : base.entrySet()) counts.addProperty(
                e.getKey()
                    .name()
                    .toLowerCase(Locale.ROOT),
                e.getValue().stackSize);
            f.add("base", counts);
            return f;
        } catch (final ReflectiveOperationException | RuntimeException e) {
            final JsonObject f = new JsonObject();
            f.addProperty("error", String.valueOf(e));
            return f;
        }
    }

    private static Object call(final Object on, final String method) throws ReflectiveOperationException {
        return on.getClass()
            .getMethod(method)
            .invoke(on);
    }

    // endregion

    // region Bacterial Vat

    /** Every Bacterial Vat recipe: its culture, what it takes and makes, its time and power, glass and sieverts. */
    private static JsonObject vat() {
        final JsonArray list = new JsonArray();
        for (final GTRecipe r : BartWorksRecipeMaps.bacterialVatRecipes.getAllRecipes()) {
            final JsonObject v = new JsonObject();
            final ItemStack dish = r.mSpecialItems instanceof ItemStack s ? s : null;
            v.addProperty(
                "culture",
                dish != null && dish.hasTagCompound() ? dish.getTagCompound()
                    .getString("Name") : null);
            v.add("items", items(r.mInputs));
            v.add("itemOutputs", items(r.mOutputs));
            v.add("fluidIn", r.mFluidInputs != null && r.mFluidInputs.length > 0 ? fluid(r.mFluidInputs[0]) : null);
            v.add("fluidOut", r.mFluidOutputs != null && r.mFluidOutputs.length > 0 ? fluid(r.mFluidOutputs[0]) : null);
            v.addProperty("durationTicks", r.mDuration);
            v.addProperty("eut", r.mEUt);
            v.addProperty("glass", r.getMetadataOrDefault(GTRecipeConstants.GLASS, 0));
            final Sievert sv = r.getMetadataOrDefault(GTRecipeConstants.SIEVERT, new Sievert(0, false));
            v.addProperty("sievert", sv.sievert);
            v.addProperty("exact", sv.isExact);
            v.addProperty("cleanroom", r.getMetadataOrDefault(GTRecipeConstants.CLEANROOM, false));
            list.add(v);
        }
        final JsonObject o = new JsonObject();
        o.addProperty("source", "bartworks' Bacterial Vat map (bw.recipe.BacteriaVat), GLASS and SIEVERT metadata");
        o.add("recipes", list);
        return o;
    }

    /** The radio hatch's materials: each item, its sieverts and its mass (from its NEI page, bw.recipe.radhatch). */
    private static JsonObject radioHatch() {
        final JsonArray list = new JsonArray();
        for (final GTRecipe r : BartWorksRecipeMaps.radioHatchFakeRecipes.getAllRecipes()) {
            if (r.mInputs == null || r.mInputs.length == 0 || r.mInputs[0] == null) continue;
            final JsonObject m = item(r.mInputs[0], 1);
            m.addProperty("sievert", r.getMetadataOrDefault(GTRecipeConstants.SIEVERT, new Sievert(0, false)).sievert);
            m.addProperty("mass", r.getMetadataOrDefault(GTRecipeConstants.MASS, 0));
            list.add(m);
        }
        final JsonObject o = new JsonObject();
        o.addProperty("source", "bartworks' radio hatch materials (bw.recipe.radhatch): SIEVERT and MASS metadata");
        o.add("materials", list);
        return o;
    }

    // endregion

    private static JsonArray items(final ItemStack[] stacks) {
        final JsonArray a = new JsonArray();
        if (stacks != null) for (final ItemStack s : stacks) if (s != null) a.add(item(s, s.stackSize));
        return a;
    }

    /** An item as the website's dataset names it, with its name in this game and an amount. */
    private static JsonObject item(final ItemStack s, final int amount) {
        final JsonObject o = new JsonObject();
        final String registry = Item.itemRegistry.getNameForObject(s.getItem());
        final String id = registry == null ? "?" : registry.toLowerCase(Locale.ROOT);
        o.addProperty("id", s.getItemDamage() == 0 ? id : id + "@" + s.getItemDamage());
        String name;
        try {
            name = s.getDisplayName();
        } catch (final RuntimeException e) {
            name = id;
        }
        o.addProperty("name", name);
        o.addProperty("amount", amount);
        return o;
    }

    private static JsonObject fluid(final FluidStack f) {
        if (f == null || f.getFluid() == null) return null;
        final JsonObject o = new JsonObject();
        o.addProperty(
            "id",
            f.getFluid()
                .getName());
        o.addProperty("name", f.getLocalizedName());
        o.addProperty("amount", f.amount);
        return o;
    }
}

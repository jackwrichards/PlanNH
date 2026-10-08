package com.gtnhplanner.power.sources;

import static com.gtnhplanner.power.sources.Helpers.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.gtnhplanner.power.PowerData;
import com.gtnhplanner.power.PowerGroup;
import com.gtnhplanner.power.PowerModel;
import com.gtnhplanner.power.PowerModel.Flow;
import com.gtnhplanner.power.PowerSetting;
import com.gtnhplanner.power.PowerSetting.Option;
import com.gtnhplanner.power.PowerSource;

/**
 * Reactors and free-energy machines (the website's sources/reactors.ts): THTR, HTGR, LFTR, the IC2 fluid reactor
 * presets, the Vacuum Reactor, DEHP, the Solar Tower and solar panels. Formulas from docs/power-planner-math.md;
 * where the workbook leaves a cost out (IC2 rods), the card says so.
 */
public final class Reactors {

    public static List<PowerSource> reactorSources() {
        return List.of(thtr(), htgr(), lftr(), ic2FluidReactor(), vacuumReactor(), dehp());
    }

    public static List<PowerSource> passiveSources() {
        return List.of(solarTower(), solarPanel());
    }

    // ---- THTR

    private static PowerSource thtr() {
        return new PowerSource(
            "thtr",
            "Thorium High Temperature Reactor",
            PowerGroup.REACTORS,
            // The controller is crafted with two circuitUltimate (ZPM) circuits.
            "ZPM",
            "Thorium pebbles to hot coolant.",
            List.of(new PowerSetting.Number("fill", "Pebble fill", 100_000, 675_000, 1000, 675_000)),
            read -> {
                final double fill = read.number("fill");
                final double efficiency = Math.min(1, 0.01 + Math.pow((fill - 100_000) / 57_500, 2) / 100);
                // MTEThoriumHighTempReactor.checkProcessing: one 648,000-tick (9 hour) operation burns
                // floor(fill x 0.5% x eff) TRISO pebbles and hands them back burned, a pebble ball per 64 and the
                // rest loose. Coolant is drawn every tick at (int)(4800 x eff) L.
                final double pebbleCost = Math.floor(fill * 0.005 * efficiency);
                final double operationSeconds = 648_000.0 / 20;
                final double hotCoolantPerSecond = Math.floor(4800 * efficiency) * 20;
                return new PowerModel(
                    // MTEThoriumHighTempReactor draws a flat RECIPE_IV/2 regardless of fill; only the coolant line
                    // scales with efficiency.
                    -3840,
                    List.of(
                        liters("Coolant", hotCoolantPerSecond),
                        items("TRISO pebble", pebbleCost / operationSeconds)),
                    List.of(
                        liters("Hot Coolant", hotCoolantPerSecond),
                        items("Burned Out TRISO pebble ball", Math.floor(pebbleCost / 64) / operationSeconds),
                        items("Burned Out TRISO pebble", (pebbleCost % 64) / operationSeconds)),
                    List.of(
                        stat("Efficiency", percent(efficiency)),
                        stat("Pebbles per cycle", formatAmount(pebbleCost)),
                        stat("Cycle", "9h"),
                        stat("Hot coolant", formatAmount(hotCoolantPerSecond / 20) + " L/t"),
                        // Primed once, never burned: emptying mode hands it all back.
                        stat("Helium charge", "730,000 L, kept")));
            });
    }

    // ---- HTGR

    /** kubatech's HTGR constants (MTEHighTempGasCooledReactor). */
    private static final double HTGR_MAX_BALLS = 10_000;
    private static final double HTGR_HELIUM = 512_000;
    /** Pebbles burned per operation per ball: (pi - 3) / 100, the source's own digits. */
    private static final double HTGR_BURN_PER_BALL = 0.00141592653589793;
    private static final double HTGR_COOLANT_SPEEDUP = 0.07 / 20;
    private static final double HTGR_WATER_SPEEDUP = 0.03 / 20;

    /** A TRISO pebble's fuel figures, as the dataset's htgrPebbles rows carry them. */
    public record Pebble(double base, double mult, double exp) {

        public static Pebble of(final PowerData.HtgrPebble row) {
            return new Pebble(row.base, row.mult, row.exp);
        }
    }

    /** What one HTGR operation burns, draws and takes (htgrOperation's result). */
    public record HtgrOperation(double efficiency, double burned, double energyMultiplier, double coolantPerTick,
        double waterPerTick, double cycleTicks, double heliumLost) {}

    /**
     * One HTGR operation at a steady full helium charge and a fill topped up every tick, as
     * MTEHighTempGasCooledReactor runs it with its hatches kept supplied.
     */
    public static HtgrOperation htgrOperation(final Pebble pebble, final double fill) {
        final double x = fill / HTGR_MAX_BALLS;
        final double efficiency = 0.1 + (1 - Math.pow(1 - x, 3)) * 0.9;
        final double burned = fill * HTGR_BURN_PER_BALL * efficiency;
        // Per-ball contributions are defined at a full reactor, so a partial fill weakens the multiplier and
        // exponent - but the fuel base is an average, and the ball count itself multiplies the coolant line below.
        final double fuelMultiplier = 1 + (pebble.mult() - 1) * x;
        final double fuelExponent = 1 + (pebble.exp() - 1) * x;
        final double energyMultiplier = pebble.base() * Math.pow(fuelMultiplier, fuelExponent);
        // checkProcessing burns the pebbles before it sizes the draw.
        final double remaining = Math.max(0, fill - burned);
        final double coolantPerTick = Math.floor(energyMultiplier * 0.5 * remaining);
        final double waterPerTick = Math.floor(energyMultiplier * 0.1 * remaining);
        // Every supplied tick adds whole ticks of progress on top of the one the base machine adds: 7% of the
        // operation per second for full coolant and 3% for full water.
        final double maxProgress = Math.floor((1 / (fuelExponent * fuelExponent)) * (2000 + 18_000 * efficiency));
        final double perTick = 1 + htgrSpeedup(maxProgress, HTGR_COOLANT_SPEEDUP, coolantPerTick)
            + htgrSpeedup(maxProgress, HTGR_WATER_SPEEDUP, waterPerTick);
        final double cycleTicks = Math.max(1, Math.ceil(maxProgress / perTick));
        // Each operation loses 0.05% of the charge, (int) truncated; the hatch tops it back up before the next one.
        final double heliumLost = HTGR_HELIUM - trunc(HTGR_HELIUM * (1 - 0.0005));
        return new HtgrOperation(
            efficiency,
            burned,
            energyMultiplier,
            coolantPerTick,
            waterPerTick,
            cycleTicks,
            heliumLost);
    }

    /**
     * onRunningTick's own expression order (drained share 1, full charge). A draw that truncates to 0 L/t drains
     * nothing, so it adds no progress.
     */
    private static double htgrSpeedup(final double maxProgress, final double rate, final double draw) {
        return draw > 0 ? trunc((maxProgress * rate * 1 * HTGR_HELIUM) / HTGR_HELIUM) : 0;
    }

    /** The dataset's names for one HTGR fuel: "Uranium-235" is "(Uranium 235)". */
    private static String htgrFuelName(final String pebble, final boolean burned) {
        return (burned ? "Burned Out TRISO Fuel" : "TRISO Fuel") + " (" + replaceFirst(pebble, "-", " ") + ")";
    }

    private static PowerSource htgr() {
        final List<PowerData.HtgrPebble> pebbles = PowerData.get().htgrPebbles;
        final List<Option> options = new ArrayList<>();
        for (final PowerData.HtgrPebble entry : pebbles) options.add(new Option(entry.name, entry.name));
        return new PowerSource(
            "htgr",
            "High Temperature Gas-cooled Reactor",
            PowerGroup.REACTORS,
            // The controller is crafted with four circuitUltimate (ZPM) circuits.
            "ZPM",
            "TRISO pebbles to coolant and steam.",
            List.of(
                new PowerSetting.Select(
                    "pebble",
                    "TRISO pebble",
                    options,
                    pebbles.isEmpty() ? "" : pebbles.get(0).name),
                // MTEHighTempGasCooledReactor will not start below 1% of its 10,000 balls.
                new PowerSetting.Number("fill", "Pebble fill", 100, 10_000, 100, 10_000)),
            read -> {
                final String pebbleName = read.select("pebble");
                final PowerData.HtgrPebble found = find(pebbles, entry -> entry.name.equals(pebbleName));
                final PowerData.HtgrPebble pebble = found != null ? found : pebbles.get(0);
                final double fill = read.number("fill");
                final HtgrOperation run = htgrOperation(Pebble.of(pebble), fill);
                final double cycleSeconds = run.cycleTicks() / 20;
                // COOLANT_PER_BALL 0.5 and WATER_PER_BALL 0.1 are per-TICK litres at full helium; steam is water
                // x160.
                return new PowerModel(
                    // RECIPE_IV x 0.2 with a full helium charge (the pump penalty is 0).
                    -1536,
                    List.of(
                        liters("Coolant", run.coolantPerTick() * 20),
                        liters("Distilled Water", run.waterPerTick() * 20),
                        items(htgrFuelName(pebble.name, false), run.burned() / cycleSeconds),
                        liters("Helium", run.heliumLost() / cycleSeconds)),
                    List.of(
                        liters("Hot Coolant", run.coolantPerTick() * 20),
                        liters("Steam", run.waterPerTick() * 160 * 20),
                        items(htgrFuelName(pebble.name, true), run.burned() / cycleSeconds)),
                    List.of(
                        stat("Efficiency", percent(run.efficiency())),
                        stat("Output multiplier", formatAmount(run.energyMultiplier())),
                        stat("Pebbles per cycle", formatAmount(run.burned())),
                        stat("Cycle", formatAmount(cycleSeconds) + "s"),
                        stat("Helium charge", "512,000 L")));
            });
    }

    // ---- LFTR

    private static final double LFTR_URANIUM_233_PER_SECOND = (20 * 5.5) / 300;

    private static PowerSource lftr() {
        final List<PowerData.LftrFuel> fuels = PowerData.get().lftrFuels;
        final List<Option> options = new ArrayList<>();
        for (final PowerData.LftrFuel entry : fuels) options.add(new Option(entry.name, entry.name));
        return new PowerSource(
            "lftr",
            "Liquid Fluoride Thorium Reactor",
            PowerGroup.REACTORS,
            // The controller is crafted around an IV Machine Hull.
            "IV",
            "Burns fuel salts for direct EU.",
            List.of(new PowerSetting.Select("fuel", "Fuel", options, fuels.isEmpty() ? "" : fuels.get(0).name)),
            read -> {
                final String fuelName = read.select("fuel");
                final PowerData.LftrFuel found = find(fuels, entry -> entry.name.equals(fuelName));
                final PowerData.LftrFuel fuel = found != null ? found : fuels.get(0);
                // RecipeLoaderLFTR burns 100 L in 100 seconds (1 L/s). Its output metadata x4 in MTENuclearReactor
                // agrees with EU/L divided by 20. Use that numeric value, not the display label (mixed-case LuV
                // breaks label parsing).
                final double euPerTick = fuel.euPerLiter / 20;
                final List<Flow> inputs = List.of(liters(fuel.name, 1), liters("Li2BeF4", 2));
                final List<Flow> outputs = new ArrayList<>();
                for (final Flow flow : List.of(
                    liters("U-Salt", fuel.uSalt / 100),
                    liters("T-Salt", fuel.tSalt / 100),
                    liters("TB-Salt", fuel.tbSalt / 100),
                    liters("UF6", fuel.uf6 / 100),
                    // MTENuclearReactor.onRunningTick, once warmed up: a 1-in-300 chance every tick of 1-10 L, so
                    // 20 x 5.5 / 300 L/s whatever the fuel (the workbook rounded it to 0.33).
                    liters("Uranium-233", LFTR_URANIUM_233_PER_SECOND))) {
                    if (flow.perSecond() > 0) outputs.add(flow);
                }
                // The recipes also drink the carrier salt: 200 L Li2BeF4 per 100 s alongside 100 L of fuel salt
                // (RecipeLoaderLFTR).
                return new PowerModel(
                    euPerTick,
                    inputs,
                    outputs,
                    List.of(stat("EU per L", formatAmount(fuel.euPerLiter))));
            });
    }

    // ---- IC2 fluid reactor

    private record Ic2Design(String key, String label, double rate) {}

    private static final List<Ic2Design> IC2_DESIGNS = List.of(
        new Ic2Design("design-1", "Design 1 (1,150 L/s)", 1150),
        new Ic2Design("design-2", "Design 2 (1,380 L/s)", 1380),
        new Ic2Design("design-3", "Design 3 (1,340 L/s)", 1340));

    private static PowerSource ic2FluidReactor() {
        final List<Option> designs = new ArrayList<>();
        for (final Ic2Design design : IC2_DESIGNS) designs.add(new Option(design.key(), design.label()));
        designs.add(new Option("custom", "Custom rate"));
        return new PowerSource(
            "ic2-fluid-reactor",
            "Nuclear Reactor (fluid mode)",
            PowerGroup.REACTORS,
            "EV",
            "A preset rod layout heating coolant.",
            List.of(
                new PowerSetting.Select("design", "Reactor design", designs, "design-2"),
                new PowerSetting.Number(
                    "customRate",
                    "Hot coolant rate",
                    1,
                    100_000,
                    10,
                    1380,
                    "L/s",
                    new PowerSetting.Condition("design", "custom"))),
            read -> {
                final String designKey = read.select("design");
                final Ic2Design design = find(
                    IC2_DESIGNS,
                    entry -> entry.key()
                        .equals(designKey));
                final double rate = design != null ? design.rate() : read.number("customRate");
                return new PowerModel(
                    0,
                    List.of(liters("Coolant", rate)),
                    List.of(liters("Hot Coolant", rate)),
                    List.of(stat("Hot coolant", formatAmount(rate) + " L/s")),
                    List.of("Uranium rod costs are not modeled; the community planner skips them too."));
            });
    }

    // ---- Vacuum Reactor

    /*
     * The Vacuum Reactor: the workbook's `4. Vac Nuke` sheet, an EU-mode IC2 reactor on its one fixed layout (40 fuel
     * rods and 14 coolant cells in the 6x9 chamber) whose cells are swapped out and recooled instead of melting. The
     * card burns rods to their depleted forms and turns cold coolant cells hot at the rate the layout heats them.
     * Recooling is not part of the card: it is the dataset's Vacuum Freezer recipe (hot cell in, cold cell out, 120
     * EU/t), a machine placed and wired back into the reactor.
     * Rod stats are transcribed from GT5U LoaderGTBlockFluid (ItemRadioactiveCellIC: cells, durability, sEnergy,
     * sHeat, mox, heat bonus) and the maths from ItemRadioactiveCellIC.processChamber. The sheet agrees except for the
     * MOX bonus: it flattens it to x2.475 for every MOX-type rod, while the game multiplies by `1 + heatBonus x heat%`
     * with a per-rod bonus (MOX 1.5, HD Plutonium 6, Excited Plutonium 2, Naquadria 1.5). The source wins.
     * Per rod: pulses p = 1 + cells/2 (single 1, dual 2, quad 3, Core 17). With n rod neighbours it pulses p + n
     * times per cell, each pulse worth sEnergy x 25 EU (IC2's x5 times the pack's nuclear = 5.0), and sheds
     * (p+n)(p+n+1) x sHeat x cells / 2 heat a second into the coolant cells beside it. The layout has 4 rods with one
     * rod neighbour, 14 with two and 22 with three.
     */
    private static final double NUKE_EU_PER_ENERGY = 25;
    /** {neighbours, rods} pairs on the layout. */
    private static final double[][] LAYOUT_RODS_BY_NEIGHBOURS = { { 1, 4 }, { 2, 14 }, { 3, 22 } };
    private static final double LAYOUT_ROD_COUNT = 40;
    private static final double LAYOUT_CELL_COUNT = 14;

    /**
     * One rod material. {@code material} is the dataset's name inside the parentheses, {@code durability} the
     * seconds a rod lasts (IC2 damages it once a second), {@code energy} and {@code heat} ItemRadioactiveCellIC
     * sEnergy and sHeat, {@code moxBonus} its heat bonus (MOX-type rods only, else null).
     */
    private record RodFamily(String material, double durability, double energy, double heat,
        @Nullable Double moxBonus) {}

    private static final List<RodFamily> ROD_FAMILIES = List.of(
        new RodFamily("Thorium", 50_000, 0.4, 1, null),
        new RodFamily("Uranium", 20_000, 2, 4, null),
        new RodFamily("MOX", 10_000, 2, 4, 1.5),
        new RodFamily("High Density Uranium", 70_000, 4, 4, null),
        new RodFamily("High Density Plutonium", 70_000, 2, 4, 6.0),
        new RodFamily("Excited Uranium", 6_000, 48, 64, null),
        new RodFamily("Excited Plutonium", 10_000, 64, 64, 2.0),
        new RodFamily("Naquadah", 100_000, 4, 4, null),
        new RodFamily("Naquadria", 100_000, 4, 4, 1.5),
        new RodFamily("Tiberium", 50_000, 2, 2, null));

    /** One fuel option of the Vacuum Reactor: a rod, its depleted form and its ItemRadioactiveCellIC figures. */
    public record VacuumFuel(String key, String label, String rod, String depleted, double cells, double durability,
        double energy, double heat, @Nullable Double moxBonus) {

        VacuumFuel withHeat(final double newHeat) {
            return new VacuumFuel(key, label, rod, depleted, cells, durability, energy, newHeat, moxBonus);
        }
    }

    private record RodSize(int cells, String prefix, String word) {}

    private static final List<RodSize> ROD_SIZES = List
        .of(new RodSize(1, "", "Single"), new RodSize(2, "Dual ", "Dual"), new RodSize(4, "Quad ", "Quad"));

    public static final List<VacuumFuel> VACUUM_FUELS = vacuumFuels();

    private static List<VacuumFuel> vacuumFuels() {
        final List<VacuumFuel> fuels = new ArrayList<>();
        for (final RodFamily family : ROD_FAMILIES) {
            for (final RodSize size : ROD_SIZES) {
                fuels.add(
                    new VacuumFuel(
                        family.material()
                            .toLowerCase(Locale.ROOT)
                            .replaceAll("\\s+", "-") + "-"
                            + size.cells(),
                        family.material() + " (" + size.word() + ")",
                        size.prefix() + "Fuel Rod (" + family.material() + ")",
                        size.prefix() + "Fuel Rod (Depleted " + family.material() + ")",
                        size.cells(),
                        family.durability(),
                        family.energy(),
                        family.heat(),
                        family.moxBonus()));
            }
        }
        fuels.add(new VacuumFuel("the-core", "The Core", "The Core", "The Core (Depleted)", 32, 100_000, 8, 4, null));
        // The quad naquadah rod sheds a quarter of its siblings' heat (sHeat 1F in the loader where the single and
        // dual say 4F); not a typo.
        fuels.replaceAll(
            fuel -> fuel.key()
                .equals("naquadah-4") ? fuel.withHeat(1) : fuel);
        return List.copyOf(fuels);
    }

    /**
     * A coolant cell by heat capacity (ItemCoolantCellIC and IC2's own three). The dataset keeps one item for a cell
     * hot or cold, so the reactor's cell output and input are the same resource, at the same rate.
     */
    public record VacuumCoolant(String key, String name, double durability) {}

    public static final List<VacuumCoolant> VACUUM_COOLANTS = List.of(
        new VacuumCoolant("coolant-10k", "10k Coolant Cell", 10_000),
        new VacuumCoolant("coolant-30k", "30k Coolant Cell", 30_000),
        new VacuumCoolant("coolant-60k", "60k Coolant Cell", 60_000),
        new VacuumCoolant("he-60k", "60k He Coolant Cell", 60_000),
        new VacuumCoolant("he-180k", "180k He Coolant Cell", 180_000),
        new VacuumCoolant("he-360k", "360k He Coolant Cell", 360_000),
        new VacuumCoolant("nak-60k", "60k NaK Coolant Cell", 60_000),
        new VacuumCoolant("nak-180k", "180k NaK Coolant Cell", 180_000),
        new VacuumCoolant("nak-360k", "360k NaK Coolant Cell", 360_000),
        new VacuumCoolant("sp-180k", "180k Sp Coolant Cell", 180_000),
        new VacuumCoolant("sp-360k", "360k Sp Coolant Cell", 360_000),
        new VacuumCoolant("sp-540k", "540k Sp Coolant Cell", 540_000),
        new VacuumCoolant("sp-1080k", "1080k Sp Coolant Cell", 1_080_000),
        new VacuumCoolant("neutronium-1g", "1G Neutronium Heat Capacitor", 1_000_000_000));

    /**
     * Heat a second into the average cell, the hottest (four three-neighbour rods pouring everything into it) and
     * the coolest (half of a one-neighbour rod plus a two-neighbour rod), per the sheet's heat map.
     */
    public record CellHeat(double average, double max, double min) {}

    /** vacuumReactorRun's result. */
    public record VacuumRun(double euPerTick, double moxMultiplier, CellHeat cellHeat) {}

    /** The reactor's EU/t and the heat its cells take, on the fixed layout. */
    public static VacuumRun vacuumReactorRun(final VacuumFuel fuel, final double coreTempPercent) {
        final double pulses = 1 + Math.floor(fuel.cells() / 2);
        final double euPerPulse = fuel.energy() * NUKE_EU_PER_ENERGY;
        final double moxMultiplier = truthy(fuel.moxBonus()) ? 1 + fuel.moxBonus() * (coreTempPercent / 100) : 1;
        final double baseHeat = (fuel.heat() * fuel.cells()) / 2;
        double euPerTick = 0;
        double totalHeat = 0;
        for (final double[] row : LAYOUT_RODS_BY_NEIGHBOURS) {
            final double neighbours = row[0], rods = row[1];
            euPerTick += rods * fuel.cells() * (pulses + neighbours) * euPerPulse;
            totalHeat += rods * heatFor(baseHeat, pulses, neighbours);
        }
        return new VacuumRun(
            euPerTick * moxMultiplier,
            moxMultiplier,
            new CellHeat(
                totalHeat / LAYOUT_CELL_COUNT,
                4 * heatFor(baseHeat, pulses, 3),
                0.5 * heatFor(baseHeat, pulses, 1) + heatFor(baseHeat, pulses, 2)));
    }

    private static double heatFor(final double baseHeat, final double pulses, final double neighbours) {
        return baseHeat * (pulses + neighbours) * (pulses + neighbours + 1);
    }

    private static PowerSource vacuumReactor() {
        final List<Option> fuels = new ArrayList<>();
        for (final VacuumFuel fuel : VACUUM_FUELS) fuels.add(new Option(fuel.key(), fuel.label()));
        final List<Option> coolants = new ArrayList<>();
        for (final VacuumCoolant coolant : VACUUM_COOLANTS) coolants.add(new Option(coolant.key(), coolant.name()));
        return new PowerSource(
            "vacuum-reactor",
            "Vacuum Reactor",
            PowerGroup.REACTORS,
            "EV",
            "Actively cooled nuke. Wire a freezer to recool its cells.",
            List.of(
                new PowerSetting.Select("fuel", "Fuel rod (x40)", fuels, "uranium-4"),
                new PowerSetting.Select("coolant", "Coolant cell (x14)", coolants, "he-360k"),
                new PowerSetting.Number("coreTemp", "Core temp", 0, 99, 1, 98, "%", null)),
            read -> {
                final String fuelKey = read.select("fuel");
                final VacuumFuel foundFuel = find(
                    VACUUM_FUELS,
                    entry -> entry.key()
                        .equals(fuelKey));
                final VacuumFuel fuel = foundFuel != null ? foundFuel : VACUUM_FUELS.get(0);
                final String coolantKey = read.select("coolant");
                final VacuumCoolant foundCoolant = find(
                    VACUUM_COOLANTS,
                    entry -> entry.key()
                        .equals(coolantKey));
                final VacuumCoolant coolant = foundCoolant != null ? foundCoolant : VACUUM_COOLANTS.get(0);
                final double coreTemp = read.number("coreTemp");
                final VacuumRun run = vacuumReactorRun(fuel, coreTemp);
                final CellHeat heat = run.cellHeat();
                final double rodsPerSecond = LAYOUT_ROD_COUNT / fuel.durability();
                final double cellLifeMin = coolant.durability() / heat.max();
                final double cellLifeAverage = coolant.durability() / heat.average();
                // The sheet's cells-to-recool: 14 cells, each swapped once per average lifespan.
                final double cellsPerSecond = LAYOUT_CELL_COUNT / cellLifeAverage;

                final List<String> warnings = new ArrayList<>();
                if (heat.max() > coolant.durability()) {
                    warnings.add(
                        coolant.name() + " bursts: the hottest cell takes "
                            + formatAmount(heat.max())
                            + " heat a second and holds "
                            + formatAmount(coolant.durability())
                            + ". Pick a bigger cell.");
                }
                if (truthy(fuel.moxBonus())) {
                    warnings.add(
                        "Core temp " + formatAmount(coreTemp)
                            + "% multiplies the output by "
                            + formatAmount(run.moxMultiplier())
                            + ". The reactor melts at 100%.");
                }
                return new PowerModel(
                    run.euPerTick(),
                    List.of(items(fuel.rod(), rodsPerSecond), items(coolant.name(), cellsPerSecond)),
                    List.of(items(fuel.depleted(), rodsPerSecond), items(coolant.name(), cellsPerSecond)),
                    List.of(
                        stat("Rod lifespan", lifespanHours(fuel.durability())),
                        stat(
                            "Cell heat",
                            formatAmount(heat.average()) + "/s avg, " + formatAmount(heat.max()) + "/s max"),
                        stat(
                            "Coolant lifespan",
                            formatAmount(cellLifeMin) + " s min, " + formatAmount(cellLifeAverage) + " s avg"),
                        stat("Cells to recool", formatAmount(cellsPerSecond * 60) + " a minute")),
                    warnings);
            });
    }

    // ---- DEHP

    private static PowerSource dehp() {
        return new PowerSource(
            "dehp",
            "Deep Earth Heating Pump",
            PowerGroup.REACTORS,
            // bartworks assembles the controller at RECIPE_IV.
            "IV",
            "Geothermal steam or hot coolant.",
            List.of(
                new PowerSetting.Select(
                    "mode",
                    "Mode",
                    List.of(new Option("steam", "Direct steam"), new Option("coolant", "Coolant heating")),
                    "steam")),
            read -> {
                if (read.select("mode")
                    .equals("coolant")) {
                    final double perSecond = 192 * 20;
                    return new PowerModel(
                        -480,
                        List.of(liters("Coolant", perSecond)),
                        List.of(liters("Hot Coolant", perSecond)),
                        List.of(stat("Hot coolant", "192 L/t")));
                }
                final double steamPerTick = 25_600;
                // MTEDeepEarthHeatingPump: waterConsume = (25600 + 160) / 160 = 161 L/t, one more than the clean
                // ratio - the game's own integer arithmetic.
                return new PowerModel(
                    -480,
                    List.of(liters("Distilled Water", 161 * 20)),
                    List.of(liters("SH Steam", steamPerTick * 20)),
                    List.of(stat("Steam", formatAmount(steamPerTick) + " L/t superheated")));
            });
    }

    // ---- Solar Tower

    private record SolarTowerRing(double rings, double hotSalt) {}

    private static final List<SolarTowerRing> SOLAR_TOWER_RINGS = List.of(
        new SolarTowerRing(1, 38.6),
        new SolarTowerRing(2, 104.6),
        new SolarTowerRing(3, 217.4),
        new SolarTowerRing(4, 431),
        new SolarTowerRing(5, 883));

    private static PowerSource solarTower() {
        final List<Option> options = new ArrayList<>();
        for (final SolarTowerRing entry : SOLAR_TOWER_RINGS) {
            options
                .add(new Option(js(entry.rings()), js(entry.rings()) + " " + (entry.rings() == 1 ? "ring" : "rings")));
        }
        return new PowerSource(
            "solar-tower",
            "Solar Tower",
            PowerGroup.PASSIVE,
            "EV",
            "Heliostats heat solar salt. No fuel.",
            List.of(new PowerSetting.Select("rings", "Heliostat rings", options, "5")),
            read -> {
                final double rings = toNumber(read.select("rings"));
                final SolarTowerRing found = find(SOLAR_TOWER_RINGS, row -> row.rings() == rings);
                final SolarTowerRing entry = found != null ? found : SOLAR_TOWER_RINGS.get(4);
                final double heliostats = (28 + 8 * rings) * rings;
                return new PowerModel(
                    0,
                    List.of(liters("Cold Solar Salt", entry.hotSalt())),
                    List.of(liters("Hot Solar Salt", entry.hotSalt())),
                    List.of(
                        stat("Heliostats", js(heliostats)),
                        stat("Hot salt", formatAmount(entry.hotSalt()) + " L/s")));
            });
    }

    // ---- Solar panels

    /**
     * MTESolarGenerator outputs V[tier] EU/t (1 for ULV): the panel's tier name and its voltage line up, so an LV
     * panel makes a full 32 EU/t.
     */
    private record SolarPanelTier(String key, String label, double eut) {}

    private static final List<SolarPanelTier> SOLAR_PANEL_TIERS = List.of(
        new SolarPanelTier("ULV", "Solar Panel (1 EU/t)", 1),
        new SolarPanelTier("LV", "LV Solar Panel (32 EU/t)", 32),
        new SolarPanelTier("MV", "MV Solar Panel (128 EU/t)", 128),
        new SolarPanelTier("HV", "HV Solar Panel (512 EU/t)", 512),
        new SolarPanelTier("EV", "EV Solar Panel (2,048 EU/t)", 2048),
        new SolarPanelTier("IV", "IV Solar Panel (8,192 EU/t)", 8192),
        new SolarPanelTier("LuV", "LuV Solar Panel (32,768 EU/t)", 32768),
        new SolarPanelTier("ZPM", "ZPM Solar Panel (131,072 EU/t)", 131072),
        new SolarPanelTier("UV", "UV Solar Panel (524,288 EU/t)", 524288));

    private static PowerSource solarPanel() {
        final List<Option> options = new ArrayList<>();
        for (final SolarPanelTier tier : SOLAR_PANEL_TIERS) options.add(new Option(tier.key(), tier.label()));
        return new PowerSource(
            "solar-panel",
            "Solar Panel",
            PowerGroup.PASSIVE,
            "MV",
            "Flat daytime EU from sunlight.",
            List.of(
                new PowerSetting.Select("panel", "Panel", options, "LV"),
                new PowerSetting.Number("duty", "Duty", 1, 100, 1, 100, "%", null)),
            read -> {
                final String panelKey = read.select("panel");
                final SolarPanelTier found = find(
                    SOLAR_PANEL_TIERS,
                    entry -> entry.key()
                        .equals(panelKey));
                final SolarPanelTier panel = found != null ? found : SOLAR_PANEL_TIERS.get(1);
                final double duty = read.number("duty") / 100;
                return new PowerModel(
                    panel.eut() * duty,
                    List.of(),
                    List.of(),
                    List.of(stat("Daytime output", formatAmount(panel.eut()) + " EU/t")));
            });
    }

    // ---- JavaScript semantics

    /** The first entry that passes, or null (Array.prototype.find). */
    @Nullable
    private static <T> T find(final List<T> list, final Predicate<T> test) {
        for (final T entry : list) {
            if (test.test(entry)) return entry;
        }
        return null;
    }

    /** A JavaScript truthiness test on an optional number: present, not zero, not NaN. */
    private static boolean truthy(@Nullable final Double value) {
        return value != null && value != 0 && !Double.isNaN(value);
    }

    /** Math.trunc. */
    private static double trunc(final double value) {
        return value < 0 ? Math.ceil(value) : Math.floor(value);
    }

    /** String.prototype.replace with a string pattern: the first occurrence only. */
    private static String replaceFirst(final String text, final String target, final String replacement) {
        final int at = text.indexOf(target);
        return at < 0 ? text : text.substring(0, at) + replacement + text.substring(at + target.length());
    }

    /** Number(string) for the plain decimal keys stored here; NaN when it does not parse. */
    private static double toNumber(final String text) {
        final String trimmed = text.trim();
        if (trimmed.isEmpty()) return 0;
        try {
            return Double.parseDouble(trimmed);
        } catch (final NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** JavaScript's String(number) for the plain values interpolated here: whole numbers print without ".0". */
    private static String js(final double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e21) return new BigDecimal(value).toPlainString();
        return Double.toString(value);
    }

    private Reactors() {}
}

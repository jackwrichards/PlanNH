package com.gtnhplanner.machines.web;

import static com.gtnhplanner.machines.web.MachineControls.*;
import static com.gtnhplanner.machines.web.MachineTable.*;

import java.util.Locale;
import java.util.Map;

/**
 * The website's machine table entries (machine-table.ts MACHINES), one for one and in its order, keyed by the names
 * the website's dataset uses; {@code aliases} cover the reference calculator's names and handler names. Shared
 * controls and rows are in {@link MachineControls}. Do not change a number here without the website.
 */
final class MachineTableEntries {

    private MachineTableEntries() {}

    /** tree-growth-simulator.ts TGS_TICKS: the Tree Growth Simulator's fixed run. */
    private static final int TGS_TICKS = 100;

    /**
     * STEAM_MULTIBLOCK: the eight bronze/steel steam multiblocks (MTESteamMacerator and its siblings). No
     * overclocking, 8 fixed parallels, duration x 1.6 / tierMachine (1 bronze, 2 high pressure): 0.625x and 1.25x
     * throughput. Steam is billed elsewhere, not by a power coefficient.
     */
    private static final Behaviour STEAM_MULTIBLOCK = machine().overclock(none())
        .parallels(8)
        .speed(c -> 0.625 * (c.tier(STEAM_PRESSURE) + 1))
        .controls(STEAM_PRESSURE_CONTROL)
        // The Steam Blender's handler inherited the mixer map's scraped pipe knob; its pipes are the build's.
        .hidesControls(PIPE);

    private static boolean distillery(final Context c) {
        return c.recipeMap != null && c.recipeMap.toLowerCase(Locale.ROOT)
            .equals("distillery");
    }

    static void register(final Map<String, Behaviour> machines) {
        // -- Heat: the machines that overclock on coil heat (each machine's setMachineHeat) --
        machines.put(
            "Blast Furnace",
            machine().overclock(HEAT)
                .heat(true, null, null)
                .aliases("Electric Blast Furnace"));
        machines.put(
            "Mega Blast Furnace",
            machine().overclock(HEAT)
                .heat(true, null, null)
                .parallels(256)
                .fullPowerPool()
                .unlimitedTierSkip()
                .aliases("Mega Electric Blast Furnace"));
        // MTEAdvEBF reads its coils raw: no voltage bonus.
        machines.put(
            "Volcanus",
            machine().overclock(HEAT)
                .speed(2.2)
                .power(0.9)
                .parallels(8)
                .note("Blazing pyrotheum is not counted."));
        // The parallel modifier ramps from 256 to 512 over continuous running; steady state is the doubled figure.
        machines.put(
            "Exothermic Hearth",
            machine().overclock(HEAT)
                .heat(true, null, null)
                .parallels(512)
                .note("Assumes fully ramped up; pyrotheum only speeds the ramp."));

        // -- Perfect overclockers --
        // MTELargeChemicalReactor reads its coil only as a structure check, so the dataset's coil knob is hidden.
        machines.put(
            "Large Chemical Reactor",
            machine().overclock(perfect())
                .hidesControls("heatingCoil"));
        // MTEMegaChemicalReactor keeps the base hatch amps but its processing logic sets unlimited tier skips.
        machines.put(
            "Mega Chemical Reactor",
            machine().overclock(perfect())
                .parallels(256)
                .unlimitedTierSkip()
                .hidesControls("heatingCoil"));
        machines.put("Circuit Assembly Line", machine().overclock(perfect()));
        machines.put("Digester", machine().overclock(perfect()));
        machines.put(
            "Elemental Duplicator",
            machine().overclock(perfect())
                .speed(2)
                .parallels(c -> 8 * c.voltageTier));
        machines.put("IsaMill Grinding Machine", machine().overclock(perfect()));
        machines.put("Flotation Cell Regulator", machine().overclock(perfect()));

        // -- Coil-driven, no heat mechanic --
        machines.put(
            "Chemical Plant",
            machine().overclock(normal())
                .aliases("ExxonMobil Chemical Plant")
                .speed(c -> c.tier(COIL) * 0.5 + 0.5)
                .parallels(c -> (c.tier(PIPE) + 1) * 2)
                .controls(FLUID_PIPE_CONTROL)
                .normalizeConfig(MachineControls::normalizeFluidPipeSettings));
        machines.put(
            "Pyrolyse Oven",
            machine().overclock(normal())
                .speed(c -> (c.tier(COIL) + 1) * 0.5));
        machines.put(
            "Oil Cracker",
            machine().overclock(normal())
                .aliases("Oil Cracking Unit")
                .power(c -> 1 - Math.min(0.5, (c.tier(COIL) + 1) * 0.1)));
        // The mega compounds 10% per coil tier with no cap: MTEMegaOilCracker.getEuModifier.
        machines.put(
            "Mega Oil Cracker",
            machine().overclock(normal())
                .parallels(256)
                .fullPowerPool()
                .unlimitedTierSkip()
                .power(c -> Math.pow(0.9, c.tier(COIL) + 1)));
        // MTEIndustrialAlloySmelter: mLevel = coil tier + 1; one perfect heat step per 900 K with no EU discount.
        machines.put(
            "Zyngen",
            machine().overclock(HEAT)
                .heat(null, 2.0, false)
                .speed(c -> 1 + (c.tier(COIL) + 1) * 0.05)
                .parallels(c -> c.voltageTier * (c.tier(COIL) + 1)));
        // MTEMultiFurnace: parallels double per coil tier from 8; every operation is a fixed 4 EU/t, 128 tick smelt.
        machines.put(
            "Multi Smelter",
            machine().overclock(normal())
                .parallels(c -> 8 * Math.pow(2, c.tier(COIL))));
        // MTEMegaAlloyBlastSmelter: duration x (1 - 5% per coil tier above TPV), as throughput; a compounding 5% EU
        // discount per coil tier above the RECIPE's own voltage tier, never a penalty.
        machines.put(
            "Mega Alloy Blast Smelter",
            machine().overclock(normal())
                .parallels(256)
                .fullPowerPool()
                .unlimitedTierSkip()
                .speed(c -> 1 / (1 - 0.05 * Math.max(0, c.tier(COIL) - 3)))
                .power(
                    c -> Math.pow(
                        0.95,
                        Math.max(
                            0,
                            c.tier(COIL) + 1 - (c.recipeVoltageTier != null ? c.recipeVoltageTier : c.voltageTier))))
                .note("Assumes a matching glass tier."));
        machines.put(
            "Large Fluid Extractor",
            machine().overclock(normal())
                .speed(c -> 1.5 + c.tier(COIL) * 0.1)
                .power(c -> 0.8 * Math.pow(0.9, c.tier(COIL)))
                .parallels(c -> (c.tier(SOLENOID) + 2) * 8));
        // MTEIndustrialThermalCentrifuge: 8 per voltage tier plus 2 per solenoid voltage tier (MV solenoid = 2).
        machines.put(
            "Large Thermal Refinery",
            machine().overclock(normal())
                .speed(c -> 2.5 + 0.05 * c.tier(COIL))
                .power(c -> 0.8 * Math.pow(0.95, c.tier(COIL)))
                .parallels(c -> c.voltageTier * 8 + (c.tier(SOLENOID) + 2) * 2));

        // -- Flat multiblocks --
        machines.put("Alloy Blast Smelter", machine().overclock(normal()));
        machines.put(
            "Big Barrel Brewery",
            machine().overclock(normal())
                .speed(1.5)
                .parallels(c -> c.voltageTier * 4));
        machines.put(
            "Boldarnator",
            machine().overclock(normal())
                .speed(3)
                .power(0.75)
                .parallels(c -> c.voltageTier * 8));
        machines.put("Bricked Blast Furnace", machine().overclock(normal()));
        machines.put("COMET - Compact Cyclotron", machine().overclock(normal()));
        // The rewritten MTECryogenicFreezer; its gelid cryotheum is not counted.
        machines.put(
            "Cryogenic Freezer",
            machine().overclock(normal())
                .speed(3)
                .power(0.9)
                .parallels(16));
        machines.put(
            "Density^2",
            machine().overclock(normal())
                .speed(2)
                .parallels(c -> Math.floor(c.voltageTier / 2.0) + 1));
        machines.put("Dissolution Tank", machine().overclock(normal()));
        machines.put("Distillation Tower", machine().overclock(normal()));
        machines.put("Implosion Compressor", machine().overclock(normal()));
        machines.put(
            "Industrial Centrifuge",
            machine().overclock(normal())
                .speed(3)
                .power(0.9)
                .parallels(c -> c.voltageTier * 8)
                .note("Assumes max speed."));
        machines.put(
            "Industrial Extrusion Machine",
            machine().overclock(normal())
                .speed(3.5)
                .parallels(c -> c.voltageTier * 6));
        machines.put(
            "Large Scale Auto-Assembler v1.01",
            machine().overclock(normal())
                .speed(3)
                .parallels(c -> c.voltageTier * 2));
        // Tower mode; distillery mode is a different recipe map and not covered.
        machines.put(
            "Mega Distillation Tower",
            machine().overclock(normal())
                .speed(1.5)
                .power(0.9)
                .parallels(256)
                .fullPowerPool()
                .unlimitedTierSkip());
        machines.put("Molecular Transformer", machine().overclock(normal()));
        machines.put(
            "Nuclear Salt Processing Plant",
            machine().overclock(normal())
                .speed(2.5)
                .parallels(c -> c.voltageTier * 2));
        machines.put(
            "Ore Washing Plant",
            machine().overclock(normal())
                .speed(5)
                .parallels(c -> c.voltageTier * 4));
        // The lanthanide beamline chambers bypass ProcessingLogic: raw duration, EU pinned to the hatch tier.
        machines.put(
            "Source Chamber",
            machine().overclock(none())
                .note("Beam energy is not modelled."));
        machines.put(
            "Target Chamber",
            machine().overclock(none())
                .note("Beam energy is not modelled."));
        machines.put(
            "Thermic Heating Device",
            machine().overclock(normal())
                .speed(2.2)
                .power(0.9)
                .parallels(c -> c.voltageTier * 8));
        machines.put(
            "TurboCan Pro",
            machine().overclock(normal())
                .speed(2)
                .parallels(c -> c.voltageTier * 8));
        machines.put("Vacuum Freezer", machine().overclock(normal()));
        machines.put(
            "Zhuhai - Fishing Port",
            machine().overclock(normal())
                .parallels(c -> (c.voltageTier + 1) * 2));

        // -- Machines whose knobs the dataset has no control for --
        machines.put(
            "Industrial Arc Furnace",
            machine().overclock(c -> custom(row(ELECTRODES, c.tier(ELECTRODE)).oc()))
                .speed(c -> row(ELECTRODES, c.tier(ELECTRODE)).speed())
                .power(c -> row(ELECTRODES, c.tier(ELECTRODE)).power())
                .parallels(c -> row(ELECTRODES, c.tier(ELECTRODE)).parallels())
                .controls(ELECTRODE_CONTROL)
                .note("Electrode durability, startup and blast mode's 16x power are not counted."));
        machines.put(
            "Industrial Cutting Factory",
            machine().overclock(normal())
                .speed(c -> row(SAWBLADES, c.tier(SAWBLADE)).speed())
                .power(c -> row(SAWBLADES, c.tier(SAWBLADE)).power())
                .parallels(c -> row(SAWBLADES, c.tier(SAWBLADE)).parallels() * c.voltageTier)
                .controls(SAWBLADE_CONTROL));
        machines.put(
            "Magnetic Flux Exhibitor",
            machine().overclock(normal())
                .speed(c -> row(ELECTROMAGNETS, c.tier(ELECTROMAGNET)).speed())
                .power(c -> row(ELECTROMAGNETS, c.tier(ELECTROMAGNET)).power())
                .parallels(c -> row(ELECTROMAGNETS, c.tier(ELECTROMAGNET)).parallels())
                .controls(ELECTROMAGNET_CONTROL));
        machines.put(
            "Industrial Autoclave",
            machine().overclock(normal())
                .speed(c -> 1.25 + c.tier(COIL) * 0.25)
                .power(c -> (11 - c.tier(PIPE)) / 12.0)
                .parallels(c -> c.tier(ITEM_PIPE) * 12 + 12)
                .controls(FLUID_PIPE_CONTROL, ITEM_PIPE_CONTROL)
                .normalizeConfig(MachineControls::normalizeFluidPipeSettings));
        machines.put(
            "Electric Implosion Compressor",
            machine().overclock(normal())
                .parallels(c -> Math.pow(4, c.tier(CONTAINMENT)))
                .controls(CONTAINMENT_CONTROL));
        machines.put(
            "Dissection Apparatus",
            machine().overclock(normal())
                .speed(3)
                .power(0.85)
                .parallels(c -> (c.tier(ITEM_PIPE) + 1) * 8)
                .controls(ITEM_PIPE_CONTROL)
                .hidesControls(PIPE));
        // MTEIndustrialForgeHammer: 6 x solenoid voltage tier x machine voltage tier (MV solenoid = 2).
        machines.put(
            "Industrial Sledgehammer",
            machine().overclock(normal())
                .speed(2)
                .parallels(c -> c.voltageTier * (c.tier(SOLENOID) + 2) * 6));
        // MTEMultiLathe: 8 parallels per ITEM pipe casing tier (tin = 1); the scraped fluid pipes are hidden.
        machines.put(
            "Industrial Precision Lathe",
            machine().overclock(normal())
                .speed(4)
                .power(0.8)
                .parallels(c -> (c.tier(ITEM_PIPE) + 1) * 8)
                .controls(ITEM_PIPE_CONTROL)
                .hidesControls(PIPE)
                .normalizeConfig(MachineControls::normalizeLatheSettings));
        // kubatech's Extreme Entity Crusher: ExtremeEntityCrusher replays the kills; this supplies the knobs, the
        // one parallel, the summed hatch power, and the ritual's quarter power for the draw check.
        machines.put(
            "Extreme Entity Crusher",
            machine().overclock(perfect())
                .power(c -> c.tier(ExtremeEntityCrusher.MODE) == 1 ? 0.25 : 1)
                .parallels(1)
                .fullPowerPool()
                .controls(ExtremeEntityCrusher.CONTROLS)
                .note("An infernal kill also drops one random enchanted item, which is not listed."));
        machines.put(
            "Industrial Maceration Stack",
            machine().overclock(normal())
                .speed(c -> c.tier(UPGRADE_CHIP) == 1 ? 6.4 : 1.6)
                .parallels(c -> (c.tier(UPGRADE_CHIP) == 1 ? 8 : 2) * c.voltageTier)
                .controls(UPGRADE_CHIP_CONTROL));
        // MTEIndustrialMixer: 1 / (1 + itemPipeTier + 1) duration, tin pipe = 3x. It takes item pipes, not the
        // scraped fluid ones.
        machines.put(
            "Industrial Mixing Machine",
            machine().overclock(normal())
                .speed(c -> c.tier(ITEM_PIPE) + 3)
                .parallels(c -> c.voltageTier * 8)
                .controls(ITEM_PIPE_CONTROL)
                .aliases("Multiblock Mixer")
                .hidesControls(PIPE));
        // The Utupu-Tanuri (MTEIndustrialDehydrator) under both its recipe maps: heat overclocks and discounts off its
        // raw coil heat, with the recipe's heat as the coil's minimum.
        final Web.Control dehydratorCoil = HEATING_COIL_CONTROL.copy();
        dehydratorCoil.minimumHeatFromSpecialValue = true;
        machines.put(
            "Multiblock Dehydrator",
            machine().aliases("Utupu-Tanuri", "Vacuum Furnace")
                .overclock(HEAT)
                .speed(2.2)
                .power(0.5)
                .parallels(4)
                .controls(dehydratorCoil));
        // MTEIndustrialWireMill: throughput 0.5 x item pipe tier, so a tin pipe runs at HALF speed.
        machines.put(
            "Industrial Wire Factory",
            machine().overclock(normal())
                .speed(c -> 0.5 * (c.tier(ITEM_PIPE) + 1))
                .power(0.75)
                .parallels(c -> c.voltageTier * 4)
                .controls(ITEM_PIPE_CONTROL)
                .hidesControls(PIPE));
        // MTEIndustrialPackager: throughput is item pipe tier + 1, tin included.
        machines.put(
            "Amazon Warehousing Depot",
            machine().overclock(normal())
                .speed(c -> c.tier(ITEM_PIPE) + 2)
                .power(0.75)
                .parallels(c -> c.voltageTier * 16)
                .controls(ITEM_PIPE_CONTROL)
                .hidesControls(PIPE));
        // MTEPreciseAssembler's normal Assembler handler; its dedicated Precise Assembler map has different math.
        machines.put(
            "Precise Auto-Assembler MT-3662",
            machine().recipeTierFromBase()
                .overclock(normal())
                .speed(2)
                .power(1)
                .parallels(c -> 16 * Math.pow(2, c.tier("preciseCasing")))
                .controls(PreciseAssembler.NORMAL_CASING, PreciseAssembler.MACHINE_CASING)
                .inputVoltageTierLimit(MachineTableEntries::prassInputVoltageLimit)
                .note(
                    "Normal Assembler mode. Requires EV+ glass. Unit casings set parallels; machine casings limit "
                        + "working voltage, with UHV unlocking all tiers."));
        machines.put(
            "Precise Assembler",
            machine().overclock(normal())
                .speed(1)
                .power(1)
                .parallels(1)
                .controls(PreciseAssembler.PRECISE_CASING, PreciseAssembler.MACHINE_CASING)
                .inputVoltageTierLimit(MachineTableEntries::prassInputVoltageLimit)
                .recipeGate(
                    c -> (c.recipeSpecialValue != null ? c.recipeSpecialValue : 0) > c.tier("preciseCasing") + 1
                        ? "This precise recipe requires a higher unit casing tier."
                        : null)
                .note(
                    "Precise mode. Unit casings unlock recipes, not extra parallels or speed. Requires EV+ glass; UHV "
                        + "machine casings remove the voltage cap."));
        // MTEIndustrialLaserEngraver caps OCs at source tier + 1 - raw recipe tier, and rejects higher recipes even if
        // the energy supply could pay.
        machines.put("Hyper-Intensity Laser Engraver", machine().overclock(c -> {
            final Rule normal = normal();
            return new Rule(
                normal.maxPerfect(),
                Math.max(
                    0,
                    Hile.sourceAt(c.tier("laserSource"))
                        .ordinal() + 1
                        - (c.recipeVoltageTier != null ? c.recipeVoltageTier : 0)),
                normal.multiplier(),
                normal.euMultiplier());
        })
            .recipeGate(c -> {
                final Hile.Source source = Hile.sourceAt(c.tier("laserSource"));
                return source.ordinal() < 13
                    && (c.recipeVoltageTier != null ? c.recipeVoltageTier : 0) > source.ordinal() + 1
                        ? "Laser source tier too low: " + source.tier()
                            + " permits recipes up to one tier above it. "
                            + "Select a higher-tier laser source and matching glass."
                        : null;
            })
            .speed(3.5)
            .power(0.8)
            .parallels(c -> Math.floor(Math.cbrt(c.value("laserSource"))))
            .controls(Hile.SOURCE_CONTROL)
            .hidesControls("laserAmperage")
            .normalizeConfig(Hile::normalizeSettings)
            .note(
                "Laser source sets parallels and the recipe/overclock ceiling; it supplies no power. Assumes glass "
                    + "at least the source tier. UEV+ sources allow one multi-amp energy hatch."));
        machines.put(
            "Transcendent Plasma Mixer",
            machine().overclock(none())
                .power(10)
                .parallels(c -> c.value("plasmaMixerParallels"))
                .controls(PLASMA_MIXER_PARALLEL_CONTROL));
        machines.put(
            "Neutron Activator",
            machine().overclock(none())
                .speed(c -> NeutronActivator.speed(c.value("speedingPipeCasing")))
                .quantiseDuration(NeutronActivator::quantiseDuration)
                .unlimitedTierSkip()
                .power(0)
                .controls(NEUTRON_PIPE_CONTROL)
                .note(
                    "Assumes neutron kinetic energy is in the recipe's range. Accelerator hatch power is not counted."));
        // MTENaquadahFuelRefinery: 4 parallels per field restriction coil tier; each coil tier above the recipe's own
        // minimum (its special value, 1 through 4) is one PERFECT overclock, and extra voltage buys nothing more.
        machines.put(
            "Naquadah Fuel Refinery",
            machine().overclock(
                c -> perfect(
                    Math.max(0, c.tier(FIELD_COIL) + 1 - (c.recipeSpecialValue != null ? c.recipeSpecialValue : 1))))
                .parallels(c -> 4 * (c.tier(FIELD_COIL) + 1))
                .unlimitedTierSkip()
                .controls(FIELD_COIL_CONTROL)
                .hidesControls(COIL));
        // Its speed boost ramps from 1 to 1.5 while it runs; steady state is the full 1.5.
        machines.put(
            "Endothermic Fridge",
            machine().overclock(c -> perfectThenNormal(c.tier("fridgeCoolant")))
                .speed(1.5)
                .parallels(256)
                .controls(COOLANT_CONTROL)
                .note("Assumes fully ramped up. Coolant consumption is not counted."));

        // -- Flat multiblocks, second batch --
        machines.put(
            "Large Electric Compressor",
            machine().overclock(normal())
                .speed(2)
                .power(0.9)
                .parallels(c -> c.voltageTier * 2));
        machines.put(
            "Hot Isostatic Pressurization Unit",
            machine().overclock(normal())
                .speed(3.5)
                .power(0.75)
                .parallels(c -> c.voltageTier * 4)
                .note("Assumes it is not overheated."));
        machines.put(
            "Neutronium Compressor",
            machine().overclock(normal())
                .parallels(8));
        // bartworks' Bacterial Vat. controlsFor and recipeGate left unset: the mod models it in machines/
        // (BacterialVat, game/MachineModels) and routes it there.
        machines.put("Bacterial Vat", machine().overclock(normal()));
        // MTETreeFarm: a fixed 100-tick run. controlsFor left unset: the mod models it in machines/
        // (TreeGrowthSimulator, game/MachineModels) and routes it there.
        machines.put(
            "Tree Growth Simulator",
            machine().overclock(none())
                .cycleTicks(TGS_TICKS)
                .parallels(1));
        machines.put(
            "Research Station",
            machine().overclock(normal())
                .aliases("Research station"));

        // -- Machines our dataset names differently from the reference --
        machines.put(
            "Multiblock Electrolyzer",
            machine().aliases("Industrial Electrolyzer")
                .overclock(normal())
                .speed(2.8)
                .power(0.9)
                .parallels(c -> c.voltageTier * 4));
        machines.put(
            "Large Sifter",
            machine().aliases("Large Sifter Control Block")
                .overclock(normal())
                .speed(5)
                .power(0.75)
                .parallels(c -> c.voltageTier * 4));
        machines.put(
            "Industrial Forming Press",
            machine().aliases("Industrial Material Press")
                .overclock(normal())
                .speed(6)
                .parallels(c -> c.voltageTier * 4));
        // "Coke Oven" is this map's name in older datasets and saved plans; the Railcraft brick Coke Oven shares the
        // name but never has the slices control, which the parallel and overclock guards key on. MTECokeOven assigns
        // recipe.mDuration directly, so a brick oven never overclocks; coils are a compounding 2% EU discount each.
        machines.put(
            "Industrial Coke Oven",
            machine().aliases("Coke Oven")
                .overclock(c -> c.value(COKE_SLICES) > 0 ? normal() : none())
                .power(c -> Math.pow(0.98, c.tier(COIL) + 1))
                .parallels(c -> {
                    final double slices = c.value(COKE_SLICES);
                    // No slices control: the Railcraft brick Coke Oven through the legacy alias, one op at a time.
                    if (slices == 0) return 1;
                    final boolean heatProof = c.tier(COKE_CASING) == 1;
                    final double base = heatProof ? 32 : 16;
                    final double perSlice = heatProof ? 16 : 8;
                    return base + (slices - 1) * perSlice;
                })
                .note("Eternal coils are needed for more than 15 slices."));
        // MTEMassFabricator ("Matter Fabrication CPU"): 64 parallels in scrap mode (the LV recipes), 8 x voltage
        // tier in UU mode.
        machines.put(
            "Matter Fabricator",
            machine().aliases("Matter Fabrication CPU")
                .overclock(perfect())
                .speed(1)
                .power(0.8)
                .parallels(c -> c.recipeVoltageTier != null && c.recipeVoltageTier == 1 ? 64 : 8 * c.voltageTier));

        // -- Remaining machines whose formulas need no recipe metadata --
        machines.put(
            "Pseudostable Black Hole Containment Field",
            machine().overclock(normal())
                .speed(5)
                .power(0.7)
                .parallels(c -> c.voltageTier * 8)
                .note("Parallels also depend on stability, which is not modelled."));

        // -- Singleblocks the dataset's raw numbers get wrong --
        // The arc furnace family runs every recipe at triple the written EU/t with 3 amps of its tier. The Plasma
        // Arc Furnace shares the map but is registered plain and must not get this entry.
        machines.put(
            "Arc Furnace",
            machine().single()
                .overclock(normal())
                .power(3)
                .amperage(3));
        // The UV+ tiers of the same family, renamed at registration.
        machines.put(
            "Short Circuit Heater",
            machine().single()
                .overclock(normal())
                .power(3)
                .amperage(3));
        // GT++'s Electric Auto Workbench: a perfect-overclocking singleblock that reaches one craft a tick at EV.
        machines.put(
            "Auto Workbench",
            machine().single()
                .overclock(perfect(3)));

        // -- Steam multiblocks: STEAM_MULTIBLOCK's logic, applied to the recipe's own base --
        machines.put("Steam Grinder", STEAM_MULTIBLOCK);
        machines.put("Steam Squasher", STEAM_MULTIBLOCK);
        machines.put("Steam Separator", STEAM_MULTIBLOCK);
        machines.put("Steam Purifier", STEAM_MULTIBLOCK);
        machines.put("Steam Presser", STEAM_MULTIBLOCK);
        machines.put("Steam Blender", STEAM_MULTIBLOCK);
        machines.put("Steam Fuser", STEAM_MULTIBLOCK);
        // MTESteamFurnaceMulti: its map is vanilla smelting (200 ticks) while the machine runs GT's 128t furnace
        // recipe; 200 / (128 x 1.6) = 0.9765625 lands on the game's own 204 ticks basic and 102 high pressure.
        machines.put(
            "Steam Hearth",
            machine().overclock(none())
                .parallels(8)
                .speed(c -> 0.9765625 * (c.tier(STEAM_PRESSURE) + 1))
                .controls(STEAM_PRESSURE_CONTROL));

        // MTESpinmatron: light mode 4x, standard and heavy 3x; heavy pays 16x EU and divides parallels by 32.
        // Parallels floor at each step like the code.
        machines.put(
            "Spinmatron-2737",
            machine().overclock(normal())
                .speed(c -> c.tier(SPIN_MODE) == 1 ? 4 : 3)
                .power(c -> 0.7 * (c.tier(SPIN_MODE) == 2 ? 16 : 1))
                .parallels(
                    c -> Math.max(
                        1,
                        Math.floor(
                            Math.floor(c.value(TURBINE_TIER) * 4 * (c.tier(SPIN_FUEL) == 1 ? 1.25 : 1))
                                / (c.tier(SPIN_MODE) == 2 ? 32 : 1))))
                .controls(SPIN_MODE_CONTROL, TURBINE_TIER_CONTROL, SPIN_FUEL_CONTROL));

        // -- Plain "N parallels per voltage tier" multis the reference never had --
        // MTEIndustrialChemicalBath: setSpeedBonus(1F / 5F), 4 * GTUtility.getTier.
        machines.put(
            "Industrial Chemical Bath",
            machine().overclock(normal())
                .speed(5)
                .parallels(c -> c.voltageTier * 4));
        // MTEIndustrialBendingMachine: setSpeedBonus(1F / 6F), 6 * GTUtility.getTier.
        machines.put(
            "Industrial Bending Machine",
            machine().overclock(normal())
                .speed(6)
                .parallels(c -> c.voltageTier * 6));
        // MTEIndustrialChisel: setSpeedBonus(1F / 3F), setEuModifier(0.75F), 16 * GTUtility.getTier.
        machines.put(
            "Industrial 3D Copying Machine",
            machine().overclock(normal())
                .speed(3)
                .power(0.75)
                .parallels(c -> c.voltageTier * 16));
        // MTEMassSolidifier: 10 * GTUtility.getTier, setEuModifier(0.8F), a momentum ramp from 1x to 3x.
        machines.put(
            "Mass Solidifier",
            machine().overclock(normal())
                .speed(3)
                .power(0.8)
                .parallels(c -> c.voltageTier * 10)
                .note("Assumes max speed."));
        // MTEFluidShaper: (2 + 3 per width expansion) * GTUtility.getTier, setEuModifier(0.8F), the same ramp.
        machines.put(
            "Fluid Shaper",
            machine().overclock(normal())
                .speed(3)
                .power(0.8)
                .parallels(c -> c.voltageTier * (2 + 3 * c.value(WIDTH_EXPANSION)))
                .controls(WIDTH_EXPANSION_CONTROL)
                .note("Assumes max speed."));
        // MTELatex: setSpeedBonus(1F / 2F), setEuModifier(0.85F), 8 * GTUtility.getTier doubled by an Elastic
        // Singularity in the controller slot.
        machines.put(
            "L.A.T.E.X.",
            machine().overclock(normal())
                .speed(2)
                .power(0.85)
                .parallels(c -> c.voltageTier * (c.tier(LATEX_SINGULARITY) == 1 ? 16 : 8))
                .controls(LATEX_SINGULARITY_CONTROL)
                .note("Rubber cost discounts are not counted."));
        // MTEAdvDistillationTower: mode follows the recipe map; Java charges 15% EU in distillery mode.
        machines.put(
            "Dangote Distillus",
            machine().overclock(normal())
                .speed(c -> distillery(c) ? 2 : 3)
                .power(c -> distillery(c) ? 0.15 : 1)
                .parallels(c -> distillery(c) ? 8 * c.voltageTier : 12)
                .recipeTierFromBase()
                .hidesControls("machineParallel", "voltageParallel")
                .note(
                    "Distillery mode assumes 12 layers: 8 parallels per voltage tier. Tower mode has 12 parallels. "
                        + "Power comes from ordinary energy hatches; multi-amp and laser hatches are not supported."));
        // goodgenerator's MTEExtremeHeatExchanger: a fixed 20-tick cycle draining up to the recipe's hot fluid once
        // per cycle, so the recipe's amounts are per second. No energy hatch, parallels or overclocks.
        machines.put(
            "Extreme Heat Exchanger",
            machine().cycleTicks(20)
                .overclock(none()));
    }

    /**
     * prassInputVoltageLimit in the table's int shape: {@link PreciseAssembler#inputVoltageLimit}'s infinity (UHV, or
     * no casing stored) becomes {@link Integer#MAX_VALUE}, which caps nothing either.
     */
    private static int prassInputVoltageLimit(final Map<String, String> settings) {
        final double limit = PreciseAssembler.inputVoltageLimit(settings);
        return Double.isInfinite(limit) ? Integer.MAX_VALUE : (int) limit;
    }
}

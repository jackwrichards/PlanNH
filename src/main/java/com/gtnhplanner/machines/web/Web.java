package com.gtnhplanner.machines.web;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.gson.JsonObject;

/**
 * The website's recipe, machine and node shapes (src/lib/model/types.ts), as plain classes Gson reads straight from its
 * JSON: the golden fixture's cases, the machine data the website exports, and what the game side builds for a card.
 * Field names are the website's; a missing field is null. Pure: no game classes.
 */
public final class Web {

    private Web() {}

    /** A resource amount: an input or output, or a control option's icon. */
    public static class Resource {

        public String kind;
        public String id;
        public double amount;
        @Nullable
        public Double chance;
        /** False for a catalyst that is never used up (a culture, a mold). */
        @Nullable
        public Boolean consumed;
        @Nullable
        public Boolean optional;
        @Nullable
        public String displayName;
        @Nullable
        public List<String> tooltip;

        public boolean isConsumed() {
            return consumed == null || consumed;
        }
    }

    /** One rung of a machine setting: its key, label, and what it does (MachineConfigTierOption). */
    public static class TierOption {

        public String key;
        public String label;
        @Nullable
        public Double heat;
        @Nullable
        public Double durationMultiplier;
        @Nullable
        public Double eutMultiplier;
        @Nullable
        public Double outputMultiplier;
        @Nullable
        public Double parallelMultiplier;
        /** Parallels that scale with the machine's voltage tier (GT++ "Voltage Tier * n Parallels"). */
        @Nullable
        public Double parallelPerVoltageTier;
        /** Additive base for voltage-scaled parallels: floor(base + n * tier). */
        @Nullable
        public Double parallelVoltageBase;
        @Nullable
        public Resource resource;

        public TierOption copy() {
            final TierOption o = new TierOption();
            o.key = key;
            o.label = label;
            o.heat = heat;
            o.durationMultiplier = durationMultiplier;
            o.eutMultiplier = eutMultiplier;
            o.outputMultiplier = outputMultiplier;
            o.parallelMultiplier = parallelMultiplier;
            o.parallelPerVoltageTier = parallelPerVoltageTier;
            o.parallelVoltageBase = parallelVoltageBase;
            o.resource = resource;
            return o;
        }
    }

    /** Typed-number entry for a setting; an absent maximum means no machine limit, values snap to {@code step}. */
    public static class Numeric {

        public double min;
        @Nullable
        public Double max;
        @Nullable
        public Double step;
    }

    /** A machine setting (MachineConfigControl): its id, label, minimum and default keys, and its rungs. */
    public static class Control {

        public String id;
        public String label;
        public String minimumKey;
        @Nullable
        public String defaultKey;
        @Nullable
        public Numeric numeric;
        /** The recipe's special value is a 1-based minimum rung on this ladder. */
        @Nullable
        public Boolean minimumFromSpecialValue;
        /** The recipe's special value is a heat in K: only coils that meet it. */
        @Nullable
        public Boolean minimumHeatFromSpecialValue;
        public List<TierOption> tiers;

        public Control copy() {
            final Control c = new Control();
            c.id = id;
            c.label = label;
            c.minimumKey = minimumKey;
            c.defaultKey = defaultKey;
            c.numeric = numeric;
            c.minimumFromSpecialValue = minimumFromSpecialValue;
            c.minimumHeatFromSpecialValue = minimumHeatFromSpecialValue;
            c.tiers = tiers;
            return c;
        }
    }

    /** A machine that runs a recipe map (MachineHandler). */
    public static class Handler {

        public String id;
        public String label;
        public String machineType;
        @Nullable
        public String minimumTier;
        @Nullable
        public String maximumTier;
        @Nullable
        public List<String> availableTiers;
        @Nullable
        public Double durationTicks;
        @Nullable
        public Double eut;
        @Nullable
        public Double maxParallel;
        @Nullable
        public Double eutLimit;
        @Nullable
        public Boolean perfectOverclock;
        /** "single" or "multiblock". */
        @Nullable
        public String kind;
        @Nullable
        public List<Control> machineConfigControls;
        @Nullable
        public String notes;

        public Handler copy() {
            final Handler h = new Handler();
            h.id = id;
            h.label = label;
            h.machineType = machineType;
            h.minimumTier = minimumTier;
            h.maximumTier = maximumTier;
            h.availableTiers = availableTiers;
            h.durationTicks = durationTicks;
            h.eut = eut;
            h.maxParallel = maxParallel;
            h.eutLimit = eutLimit;
            h.perfectOverclock = perfectOverclock;
            h.kind = kind;
            h.machineConfigControls = machineConfigControls;
            h.notes = notes;
            return h;
        }
    }

    /** The handler-applied machine's facts on a recipe (MachineProfile). */
    public static class Profile {

        @Nullable
        public String machineType;
        @Nullable
        public String minimumTier;
        @Nullable
        public String maximumTier;
        @Nullable
        public List<String> availableTiers;
        @Nullable
        public Double durationTicks;
        @Nullable
        public Double eut;
        @Nullable
        public Double maxParallel;
        @Nullable
        public Double eutLimit;
        @Nullable
        public Boolean perfectOverclock;
        @Nullable
        public String kind;
        @Nullable
        public String notes;
    }

    public static class Source {

        @Nullable
        public String recipeMap;
        @Nullable
        public String rawRecipeId;
    }

    public static class Nei {

        @Nullable
        public List<String> additionalInfo;
    }

    /** A recipe as the website's solver reads it (Recipe). */
    public static class Recipe {

        public String id;
        public String name;
        @Nullable
        public String kind;
        @Nullable
        public String category;
        public String machineType;
        public String minimumTier;
        @Nullable
        public String maximumTier;
        @Nullable
        public List<String> availableTiers;
        public double durationTicks;
        public double eut;
        public List<Resource> inputs;
        public List<Resource> outputs;
        @Nullable
        public String programmedCircuit;
        @Nullable
        public Double specialValue;
        @Nullable
        public Profile machineProfile;
        @Nullable
        public List<Handler> machineHandlers;
        @Nullable
        public List<Control> machineConfigControls;
        /** Power cards only: the generator this recipe was built from. */
        @Nullable
        public JsonObject power;
        @Nullable
        public Source source;
        @Nullable
        public JsonObject metadata;
        @Nullable
        public Nei nei;

        /** A shallow copy, as the website's {@code {...recipe}}. */
        public Recipe copy() {
            final Recipe r = new Recipe();
            r.id = id;
            r.name = name;
            r.kind = kind;
            r.category = category;
            r.machineType = machineType;
            r.minimumTier = minimumTier;
            r.maximumTier = maximumTier;
            r.availableTiers = availableTiers;
            r.durationTicks = durationTicks;
            r.eut = eut;
            r.inputs = inputs;
            r.outputs = outputs;
            r.programmedCircuit = programmedCircuit;
            r.specialValue = specialValue;
            r.machineProfile = machineProfile;
            r.machineHandlers = machineHandlers;
            r.machineConfigControls = machineConfigControls;
            r.power = power;
            r.source = source;
            r.metadata = metadata;
            r.nei = nei;
            return r;
        }
    }

    /** The node fields the machine maths read (FactoryNode). */
    public static class Node {

        @Nullable
        public String overclockTier;
        @Nullable
        public String hatchVoltageTier;
        @Nullable
        public Double hatchAmps;
        @Nullable
        public Double energyHatches;
        @Nullable
        public String energyHatchType;
        /** "amps" or "eut". */
        @Nullable
        public String powerInputMode;
        @Nullable
        public Double powerEuT;
        @Nullable
        public String coilTier;
        @Nullable
        public Map<String, String> machineConfigTiers;
        @Nullable
        public String machineHandlerId;
        @Nullable
        public Double parallel;
        @Nullable
        public Double machineCount;
    }
}

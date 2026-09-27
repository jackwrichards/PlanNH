package com.sbancuz.plannh.data.provider.gregtech.probe;

import static com.sbancuz.plannh.data.Settings.GT_COIL;
import static com.sbancuz.plannh.data.Settings.GT_ELECTRODE;
import static com.sbancuz.plannh.data.Settings.GT_ITEM_PIPE;
import static com.sbancuz.plannh.data.Settings.GT_PIPE_CASING;
import static com.sbancuz.plannh.data.Settings.GT_SAWBLADE;
import static com.sbancuz.plannh.data.Settings.GT_SOLENOID;
import static com.sbancuz.plannh.data.Settings.GT_STRUCTURE_TIER;
import static com.sbancuz.plannh.data.Settings.GT_WIDTH;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.minecraft.util.MathHelper;

import com.sbancuz.plannh.data.Reflect;
import com.sbancuz.plannh.data.Settings;
import com.sbancuz.plannh.data.provider.gregtech.GTSettings;
import com.sbancuz.plannh.data.provider.gregtech.StructureState;

import gregtech.api.enums.HeatingCoilLevel;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;

import kubatech.loaders.ArcFurnaceElectrode;

/**
 * Writes the structure a player would have built into the fields the machine reads. A machine's own
 * checkMachine reduces the blocks around it to a handful of fields and does arithmetic on those, so
 * writing the fields directly asks the machine the same question a built one would answer.
 *
 * <p>
 * {@link FieldRole} is the whole of what that costs: one entry per kind of field, saying what it
 * holds and how to write it.
 */
public final class StructureWriter {

    /**
     * What one of GregTech's fields holds, and how to write it. A field carrying a GregTech type of
     * its own is recognised by that type, a plain number by name; type wins where both could match.
     */
    private enum FieldRole {

        COIL_LEVEL(GT_COIL, HeatingCoilLevel.class, FieldRole::coilLevel),
        COIL_TIER(GT_COIL, "mCoilTier"),
        /** GT++ stores coilTier + 1, so an absent coil reads as one rather than none. */
        COIL_TIER_FROM_ONE(GT_COIL, (field, machine, tier) -> setNumber(field, machine, tier + 1), "mLevel"),
        /**
         * Kelvin, not a tier: the machines that keep one derive it in checkMachine, so the probe has to
         * supply it. What the coil alone supplies, so a machine adding a voltage term reads low.
         */
        COIL_HEAT(GT_COIL, (field, machine, tier) -> setNumber(field, machine, GTSettings.coilHeat(tier)),
            "mHeatingCapacity"),
        ELECTRODE_ITEM(GT_ELECTRODE, ArcFurnaceElectrode.class, FieldRole::electrode),
        ITEM_PIPE_TIER(GT_ITEM_PIPE, "itemPipeTier"),
        SOLENOID_TIER(GT_SOLENOID, "solenoidLevel"),
        PIPE_CASING_TIER(GT_PIPE_CASING, "mPipeCasingTier", "tierPipeCasing", "checkPipe"),
        /** mTier is deliberately absent: the controller's own voltage tier, which the energy hatch supplies. */
        CASING_TIER(
            GT_STRUCTURE_TIER,
            "tier",
            "controllerTier",
            "structureTier",
            "mSolidCasingTier",
            "mMachineCasingTier",
            "tierMachineCasing"),
        SLICES(GT_WIDTH, "width", "height", "mHeight");

        /** Puts one setting onto the field the machine keeps it in. */
        @FunctionalInterface
        private interface Setter {
            void set(Field field, MTEMultiBlockBase machine, int value) throws ReflectiveOperationException;
        }

        private final Settings setting;
        private final Class<?> type;
        private final List<String> names;
        private final Setter setter;

        FieldRole(final Settings setting, final Class<?> type, final Setter setter) {
            this.setting = setting;
            this.type = type;
            this.names = List.of();
            this.setter = setter;
        }

        /** The default: a plain number, in whichever of the four forms the machine declares it. */
        FieldRole(final Settings setting, final String... names) {
            this.setting = setting;
            this.type = null;
            this.names = List.of(names);
            this.setter = FieldRole::setNumber;
        }

        FieldRole(final Settings setting, final Setter setter, final String... names) {
            this.setting = setting;
            this.type = null;
            this.names = List.of(names);
            this.setter = setter;
        }

        /** GregTech stores a plain number as int, byte or a boxed Byte depending on the machine. */
        private static final Map<Class<?>, Setter> NUMBERS = Map.<Class<?>, Setter>of(
            int.class, (field, machine, value) -> field.setInt(machine, value),
            byte.class, (field, machine, value) -> field.setByte(machine, (byte) value),
            Byte.class, (field, machine, value) -> field.set(machine, (byte) value),
            Integer.class, (field, machine, value) -> field.set(machine, value));

        /** Both indexes are built from the constants, and {@code toMap} rejects a second claim on a key. */
        private static final Map<Class<?>, FieldRole> BY_TYPE = Arrays.stream(values())
            .filter(role -> role.type != null)
            .collect(Collectors.toMap(role -> role.type, role -> role));

        private static final Map<String, FieldRole> BY_NAME = Arrays.stream(values())
            .flatMap(role -> role.names.stream()
                .map(name -> Map.entry(name, role)))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        /** The role a field plays, or null when it is not one of ours. */
        @Nullable
        static FieldRole of(final Field field) {
            final Class<?> type = field.getType();
            final FieldRole byType = BY_TYPE.get(type);
            if (byType != null) return byType;
            return NUMBERS.containsKey(type) ? BY_NAME.get(field.getName()) : null;
        }

        void write(final Field field, final MTEMultiBlockBase machine, final StructureState state)
            throws ReflectiveOperationException {
            setter.set(field, machine, state.get(setting));
        }

        /** The default: the setting as a plain number, in whichever form the machine declares it. */
        private static void setNumber(final Field field, final MTEMultiBlockBase machine, final int value)
            throws ReflectiveOperationException {
            NUMBERS.get(field.getType()).set(field, machine, value);
        }

        private static void coilLevel(final Field field, final MTEMultiBlockBase machine, final int tier)
            throws ReflectiveOperationException {
            field.set(machine,
                HeatingCoilLevel.getFromTier((byte) MathHelper.clamp_int(tier, 0, GTSettings.MAX_COIL_TIER)));
        }

        /** Null when the electrodes are not registered yet: the machine's default survives, null does not. */
        private static void electrode(final Field field, final MTEMultiBlockBase machine, final int tier)
            throws ReflectiveOperationException {
            final ArcFurnaceElectrode value = ArcFurnaceElectrode
                .getById(MathHelper.clamp_int(tier, 0, GTSettings.MAX_ELECTRODE_TIER));
            if (value != null) {
                field.set(machine, value);
            }
        }
    }

    private final List<FieldWrite> writes;
    private final EnumSet<Settings> settings;

    /** One field of the machine, and the role that says what to put in it. */
    private record FieldWrite(Field field, FieldRole role) {}

    private StructureWriter(final List<FieldWrite> writes, final EnumSet<Settings> settings) {
        this.writes = writes;
        this.settings = settings;
    }

    @Nonnull
    public static StructureWriter forClass(final Class<?> machineClass) {
        final List<FieldWrite> found = new ArrayList<>();
        final EnumSet<Settings> reachable = EnumSet.noneOf(Settings.class);
        for (Class<?> c = machineClass; c != null; c = c.getSuperclass()) {
            for (final Field field : c.getDeclaredFields()) {
                final FieldRole role = FieldRole.of(field);
                if (role == null) continue;
                found.add(new FieldWrite(Reflect.accessible(field), role));
                reachable.add(role.setting);
            }
        }
        return new StructureWriter(List.copyOf(found), reachable);
    }

    /** The settings this machine could possibly read. The sensitivity scan narrows it to those it does. */
    @Nonnull
    public EnumSet<Settings> reachableSettings() {
        return EnumSet.copyOf(settings);
    }

    /**
     * Every setting the probe can offer a row for: the setting of each {@link FieldRole}, plus the
     * sawblade, which has no field to be recognised by. The mode has neither a field nor a role: it is
     * written straight to GregTech's public field and offered from the public mode count. The canary
     * test asserts its rows cover this set, so a role without a canary fails the build.
     */
    @Nonnull
    public static EnumSet<Settings> recognizedSettings() {
        final EnumSet<Settings> all = EnumSet.of(GT_SAWBLADE);
        for (final FieldRole role : FieldRole.values()) {
            all.add(role.setting);
        }
        return all;
    }

    /**
     * Writes the state onto the machine. The mode and the sawblade are the two settings the structure
     * keeps outside a field; the rest are one per discovered field. A field that refuses the write is
     * skipped rather than abandoning the rest.
     */
    void apply(@Nonnull final MTEMultiBlockBase machine, @Nonnull final StructureState state) {
        machine.machineMode = state.get(Settings.GT_MODE);
        for (final FieldWrite write : writes) {
            try {
                write.role().write(write.field(), machine, state);
            } catch (final ReflectiveOperationException | RuntimeException skip) {
                // Left as the machine's own default, which is what an unprobed setting already means.
            }
        }
    }
}

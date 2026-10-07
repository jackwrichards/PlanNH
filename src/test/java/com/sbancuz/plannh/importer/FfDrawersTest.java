package com.sbancuz.plannh.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.sbancuz.plannh.data.flowchart.Drawer;
import com.sbancuz.plannh.importer.FfDrawers.Role;
import com.sbancuz.plannh.importer.FfDrawers.Target;
import com.sbancuz.plannh.importer.FfPlan.FfStorage;

/** FF drawer roles (storage-role.ts) and rate rules (storage-target.ts), and what they become. */
class FfDrawersTest {

    private static FfStorage storage(@Nullable final String drainMode, @Nullable final Double target,
        @Nullable final String targetMode, @Nullable final String poolSide) {
        return new FfStorage("s", "item", "a:b", "AB", drainMode, null, target, targetMode, null, poolSide, 0, 0);
    }

    private static FfStorage storage(@Nullable final Double target, @Nullable final String targetMode) {
        return storage(null, target, targetMode, null);
    }

    @Test
    void theRoleComesFromTheWires() {
        final FfStorage plain = storage(null, null);
        assertEquals(Role.PRODUCT, FfDrawers.roleFor(plain, true, false, false), "fed only: a product by default");
        assertEquals(Role.BYPRODUCT, FfDrawers.roleFor(storage("byproduct", null, null, null), true, false, false));
        assertEquals(Role.TRASH, FfDrawers.roleFor(storage("trash", null, null, null), true, false, false));
        assertEquals(Role.SOURCE, FfDrawers.roleFor(plain, false, true, false), "drawn from only: a source");
        assertEquals(Role.BUFFER, FfDrawers.roleFor(plain, true, true, false), "both: a buffer");
        assertEquals(Role.IDLE, FfDrawers.roleFor(plain, false, false, false));
        assertEquals(Role.SOURCE, FfDrawers.roleFor(storage(-4.0, null), false, false, false), "an input rate");
        assertEquals(Role.PRODUCT, FfDrawers.roleFor(storage(null, null, null, "drain"), false, false, false));
    }

    @Test
    void poolPlansGoByTheDrawersSide() {
        assertEquals(Role.SOURCE, FfDrawers.roleFor(storage(null, null, null, "source"), true, false, true));
        assertEquals(Role.PRODUCT, FfDrawers.roleFor(storage(null, null), true, false, true));
        assertEquals(Role.IDLE, FfDrawers.roleFor(storage(null, null), true, true, true), "the pool is the buffer");
        assertEquals(Role.SOURCE, FfDrawers.roleFor(storage(-1.0, null), true, false, true));
    }

    @Test
    void rolesAreReadOffAWholePlan() {
        final FfPlan plan = FfPlanParser.parse(FakeGame.fixture("pa-cell-loop-plan.json"));
        final Map<String, Role> roles = FfDrawers.roles(plan);
        assertEquals(Role.SOURCE, roles.get("storage-034d3bc8-d034-4c14-84e6-7d6eca67df98"), "water");
        assertEquals(Role.PRODUCT, roles.get("storage-77f32a3c-854e-42fe-929b-27374c9bd555"), "hydrogen");
        assertEquals(Role.BUFFER, roles.get("storage-2cc93116-491d-4c41-a9fb-f7ee1396f986"), "empty cells");
    }

    @Test
    void rolesBecomePlanNhDrawerKinds() {
        assertEquals(Drawer.Kind.SOURCE, FfDrawers.kindOf(Role.SOURCE));
        assertEquals(Drawer.Kind.PRODUCT, FfDrawers.kindOf(Role.PRODUCT));
        assertEquals(Drawer.Kind.BYPRODUCT, FfDrawers.kindOf(Role.BYPRODUCT));
        assertEquals(Drawer.Kind.TRASH, FfDrawers.kindOf(Role.TRASH));
        assertNull(FfDrawers.kindOf(Role.BUFFER));
        assertNull(FfDrawers.kindOf(Role.IDLE));
    }

    @Test
    void rulesFollowFactoryFlowsDefaults() {
        assertEquals(new Target(Drawer.Rule.AT_LEAST, 10), FfDrawers.target(storage(10.0, null), Role.PRODUCT, true));
        assertEquals(
            new Target(Drawer.Rule.EXACTLY, 5),
            FfDrawers.target(storage(-5.0, null), Role.SOURCE, true),
            "an input is exact by default, and the size of its negative rate");
        assertEquals(
            new Target(Drawer.Rule.AT_LEAST, 5),
            FfDrawers.target(storage(-5.0, "at-least"), Role.SOURCE, true));
        assertEquals(new Target(Drawer.Rule.AT_MOST, 3), FfDrawers.target(storage(3.0, "at-most"), Role.PRODUCT, true));
        assertEquals(new Target(Drawer.Rule.EXACTLY, 3), FfDrawers.target(storage(3.0, "exact"), Role.PRODUCT, true));
        assertEquals(Target.NONE, FfDrawers.target(storage(3.0, "ignore"), Role.PRODUCT, true));
        assertEquals(Target.NONE, FfDrawers.target(storage(null, "exact"), Role.PRODUCT, true), "no number, no rule");
        assertEquals(Target.NONE, FfDrawers.target(storage(0.0, null), Role.PRODUCT, true), "at least 0 asks nothing");
        assertEquals(new Target(Drawer.Rule.AT_MOST, 0), FfDrawers.target(storage(0.0, "at-most"), Role.PRODUCT, true));
    }

    @Test
    void rulesComeOnlyFromSolvePlansAndOnlyForSourcesAndProducts() {
        assertEquals(Target.NONE, FfDrawers.target(storage(10.0, null), Role.PRODUCT, false), "Build ignores them");
        assertEquals(Target.NONE, FfDrawers.target(storage("byproduct", 10.0, null, null), Role.BYPRODUCT, true));
        assertEquals(Target.NONE, FfDrawers.target(storage("trash", 10.0, null, null), Role.TRASH, true));
    }

    @Test
    void readsTheLegacyPoolRule() {
        final FfStorage ignored = new FfStorage("s", "item", "a", null, null, null, 4.0, null, "ignore", null, 0, 0);
        assertEquals("ignore", FfDrawers.targetMode(ignored, Role.PRODUCT));
        final FfStorage exact = new FfStorage("s", "item", "a", null, null, null, 4.0, null, "exact", null, 0, 0);
        assertEquals("exact", FfDrawers.targetMode(exact, Role.PRODUCT));
    }
}

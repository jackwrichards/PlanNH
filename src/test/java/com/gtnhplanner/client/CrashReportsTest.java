package com.gtnhplanner.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Telling the planner's crashes from the rest, and the error in a line, from the game's crash reports. */
class CrashReportsTest {

    private static final String OURS = """
        ---- Minecraft Crash Report ----
        // Don't be sad, have a hug! <3

        Time: 10/9/26 10:56 AM
        Description: Ticking screen

        java.lang.NullPointerException: Cannot read field "inputs" because "m" is null
        \tat Launch//com.gtnhplanner.ui.card.CardModel.of(CardModel.java:206)
        \tat Launch//com.gtnhplanner.ui.BoardSession.tickBoard(BoardSession.java:1549)
        \tat net.minecraft.client.Minecraft.runTick(Minecraft.java:1700)


        A detailed walkthrough of the error, its code path and all known details is as follows:
        ---------------------------------------------------------------------------------------
        """;

    private static final String THEIRS = """
        ---- Minecraft Crash Report ----
        Description: Unexpected error

        java.lang.IllegalStateException: Someone else's bug
        \tat gregtech.api.Thing.tick(Thing.java:12)
        \tat net.minecraft.client.Minecraft.runTick(Minecraft.java:1700)


        A detailed walkthrough of the error, its code path and all known details is as follows:
        -- System Details --
        \tFML: gtnhplanner{0.2.0} [GTNH Planner] (gtnhplanner.jar) UCHIJAAAA
        \tat Launch//com.gtnhplanner.SomeThread.run(SomeThread.java:1)
        """;

    @Test
    void aCrashThroughThePlannersCodeIsOurs() {
        assertTrue(CrashReports.plannerAtFault(OURS));
    }

    @Test
    void theModListAndOtherThreadsDoNotCount() {
        assertFalse(CrashReports.plannerAtFault(THEIRS), "only the error that ended the game counts");
    }

    @Test
    void aToolkitErrorWhileThePlannerIsOpenIsOursToo() {
        final String toolkit = """
            ---- Minecraft Crash Report ----
            Description: Rendering screen

            java.lang.IndexOutOfBoundsException: Index 0 out of bounds for length 0
            \tat java.base/java.util.ArrayList.get(ArrayList.java:428)
            \tat Launch//com.cleanroommc.modularui.widgets.textfield.TextFieldRenderer.drawMeasuredLines(TextFieldRenderer.java:93)
            \tat Launch//net.minecraft.client.renderer.EntityRenderer.updateCameraAndRender(EntityRenderer.java:1136)


            A detailed walkthrough of the error, its code path and all known details is as follows:
            -- System Details --
            """;
        assertTrue(
            CrashReports
                .plannerAtFault(toolkit + "\tGTNH Planner: 0.2.0, plan 'x' (1 cards, 0 drawers), the planner open\n"));
        assertFalse(
            CrashReports.plannerAtFault(toolkit + "\tGTNH Planner: 0.2.0, screen GuiChest\n"),
            "another mod's ModularUI screen is not the planner's");
    }

    @Test
    void theErrorInALine() {
        assertEquals(
            "java.lang.NullPointerException: Cannot read field \"inputs\" because \"m\" is null",
            CrashReports.summary(OURS));
        assertEquals("The game crashed", CrashReports.summary("not a crash report"));
    }
}

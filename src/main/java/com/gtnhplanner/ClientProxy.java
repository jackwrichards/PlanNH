package com.gtnhplanner;

import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.input.Keyboard;

import com.gtnhplanner.client.ChatHandler;
import com.gtnhplanner.client.ImportCommand;
import com.gtnhplanner.client.WorldHandler;
import com.gtnhplanner.dev.DevHarness;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;

public class ClientProxy extends CommonProxy {

    private static final KeyBinding openFlowchartKey = new KeyBinding(
        "key.neiflowchart.open",
        Keyboard.KEY_F8,
        "key.categories.neiflowchart");

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
    }

    @Override
    public void init(final FMLInitializationEvent event) {
        super.init(event);

        // Vanilla has to go first since the furnace handler is likely to be overwritten
        Compat.init();

        ClientRegistry.registerKeyBinding(openFlowchartKey);
        // Playing with the planner closed: its keys, the minimap.
        com.gtnhplanner.ui.world.PlannerKeys.register();
        FMLCommonHandler.instance()
            .bus()
            .register(new com.gtnhplanner.ui.world.PlannerKeys());
        MinecraftForge.EVENT_BUS.register(com.gtnhplanner.ui.world.Minimap.INSTANCE);
        // Plan cards placed on blocks: placing them, seeing them, and the plan overlaid on the world.
        for (final Object world : new Object[] { com.gtnhplanner.ui.world.LinkPicker.INSTANCE,
            com.gtnhplanner.ui.world.LinkTarget.INSTANCE, com.gtnhplanner.ui.world.WorldView.INSTANCE,
            com.gtnhplanner.ui.world.PlanOverlay.INSTANCE, com.gtnhplanner.ui.world.PlacementKeys.INSTANCE }) {
            MinecraftForge.EVENT_BUS.register(world);
            FMLCommonHandler.instance()
                .bus()
                .register(world);
        }

        final WorldHandler handler = new WorldHandler();
        MinecraftForge.EVENT_BUS.register(handler);
        FMLCommonHandler.instance()
            .bus()
            .register(handler);

        MinecraftForge.EVENT_BUS.register(new ChatHandler());
        ClientCommandHandler.instance.registerCommand(new ImportCommand());

        FMLCommonHandler.instance()
            .bus()
            .register(this);

        DevHarness.initIfDev();
    }

    @SubscribeEvent
    public void onKeyInput(final InputEvent.KeyInputEvent event) {
        if (openFlowchartKey.isPressed()) {
            com.gtnhplanner.ui.Planner.open();
        }
    }
}

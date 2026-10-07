package com.gtnhplanner;

import com.gtnhplanner.annotation.VersionedInjector;
import com.gtnhplanner.client.ImportCommand;
import com.gtnhplanner.config.ConfigMain;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

public class CommonProxy {

    public void preInit(final FMLPreInitializationEvent event) {
        VersionedInjector.injectAll(event.getAsmData());

        Config.synchronizeConfiguration(event.getSuggestedConfigurationFile());
        ConfigMain.registerConfigs();
    }

    public void init(final FMLInitializationEvent event) {}

    public void postInit(final FMLPostInitializationEvent event) {

    }

    public void serverStarting(final FMLServerStartingEvent event) {
        if (!event.getServer()
            .isDedicatedServer()) {
            event.registerServerCommand(new ImportCommand());
        }
    }
}

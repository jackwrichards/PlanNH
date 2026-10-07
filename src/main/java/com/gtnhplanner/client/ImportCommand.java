package com.gtnhplanner.client;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.StatCollector;

import com.gtnhplanner.api.PlanAPI;
import com.gtnhplanner.data.flowchart.Graph;

/**
 * Client-side command that processes an incoming GTNH Planner share.
 */
public class ImportCommand extends CommandBase {

    public static final String COMMAND_NAME = "gtnhplanner_import";

    @Override
    public String getCommandName() {
        return COMMAND_NAME;
    }

    @Override
    public String getCommandUsage(final ICommandSender sender) {
        return "/" + COMMAND_NAME + " <nbt>";
    }

    @Override
    public void processCommand(final ICommandSender sender, final String[] args) {
        if (args.length == 0) {
            sender.addChatMessage(
                new ChatComponentText(StatCollector.translateToLocal("gtnhplanner.share.import_error.missing")));
            return;
        }

        final String joined = String.join(" ", args);
        final Graph graph = PlanAPI.importFromNBT(joined);
        if (graph == null) {
            sender.addChatMessage(
                new ChatComponentText(StatCollector.translateToLocal("gtnhplanner.share.import_error.invalid")));
            return;
        }

        PlanAPI.importGraph(graph);

        com.gtnhplanner.ui.Planner.open();
    }

    @Override
    public boolean canCommandSenderUseCommand(final ICommandSender sender) {
        return true;
    }
}

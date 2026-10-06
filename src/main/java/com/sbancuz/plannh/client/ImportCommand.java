package com.sbancuz.plannh.client;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.StatCollector;

import com.sbancuz.plannh.api.PlanAPI;
import com.sbancuz.plannh.data.flowchart.Graph;

/**
 * Client-side command that processes an incoming PlanNH share.
 */
public class ImportCommand extends CommandBase {

    public static final String COMMAND_NAME = "plannh_import";

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
                new ChatComponentText(StatCollector.translateToLocal("plannh.share.import_error.missing")));
            return;
        }

        final String joined = String.join(" ", args);
        final Graph graph = PlanAPI.importFromNBT(joined);
        if (graph == null) {
            sender.addChatMessage(
                new ChatComponentText(StatCollector.translateToLocal("plannh.share.import_error.invalid")));
            return;
        }

        PlanAPI.importGraph(graph);

        com.sbancuz.plannh.ui.Planner.open();
    }

    @Override
    public boolean canCommandSenderUseCommand(final ICommandSender sender) {
        return true;
    }
}

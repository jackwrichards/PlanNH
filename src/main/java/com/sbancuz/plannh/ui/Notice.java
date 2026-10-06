package com.sbancuz.plannh.ui;

import java.util.List;
import java.util.UUID;

import com.sbancuz.plannh.data.flowchart.balancer.Severity;

/**
 * One line along the top of the board: something about the plan worth fixing or knowing, and the cards and drawers
 * it is about, which "Show me" frames.
 */
public record Notice(Severity severity, String text, List<UUID> focus) {}

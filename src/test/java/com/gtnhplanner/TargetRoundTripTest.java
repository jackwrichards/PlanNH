package com.gtnhplanner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Serializer;
import com.gtnhplanner.harness.GtnhFlowLoader;
import com.gtnhplanner.harness.GtnhFlowLoader.LoadedChart;

/** Target pins must survive save/load: a lost pin silently unpins the chart. */
class TargetRoundTripTest {

    @Test
    void targetOutputRatesSurviveEncodeDecode() {
        final LoadedChart chart = GtnhFlowLoader.load("mk1");
        final Node fusion = chart.machine(0);
        fusion.targetOutputRates.put(0, 12.5);

        final Graph decoded = Serializer.decode(Serializer.encode(chart.graph()));

        final Node restored = decoded.nodes.get(fusion.id);
        assertEquals(12.5, restored.targetOutputRates.get(0), 1e-9, "the target rate itself");
        assertEquals(fusion.targetOutputRates.size(), restored.targetOutputRates.size());
    }
}

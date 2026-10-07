package com.gtnhplanner.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.ui.theme.Fmt;
import com.gtnhplanner.ui.theme.Fmt.RateUnit;

/** The board's numbers must read the same as Factory Flow's. */
class FmtTest {

    @Test
    void compactFollowsFactoryFlow() {
        assertEquals("0", Fmt.compact(0));
        assertEquals("0.25", Fmt.compact(0.25));
        assertEquals("0.033", Fmt.compact(0.03333));
        assertEquals("1", Fmt.compact(1));
        assertEquals("12.5", Fmt.compact(12.5));
        assertEquals("900", Fmt.compact(900));
        assertEquals("123.5", Fmt.compact(123.45));
        assertEquals("6.93k", Fmt.compact(6930));
        assertEquals("180k", Fmt.compact(180_000));
        assertEquals("1.2M", Fmt.compact(1_200_000));
        assertEquals("1M", Fmt.compact(999_999.9));
        assertEquals("-900", Fmt.compact(-900));
    }

    @Test
    void briefKeepsThreeFiguresSoNothingIsCut() {
        assertEquals("0", Fmt.brief(0));
        assertEquals("0.33", Fmt.brief(1 / 3.0));
        assertEquals("33.3", Fmt.brief(33.333));
        assertEquals("-167", Fmt.brief(-166.67));
        assertEquals("100", Fmt.brief(99.96));
        assertEquals("999", Fmt.brief(999.4));
        assertEquals("1k", Fmt.brief(999.6));
        assertEquals("33.3k", Fmt.brief(33_333));
        assertEquals("2.56M", Fmt.brief(2_560_000));
    }

    @Test
    void ratesCarryTheUnitAndLitres() {
        assertEquals("900/hr", Fmt.rate(0.25, RateUnit.HOUR, false));
        assertEquals("180k L/hr", Fmt.rate(50, RateUnit.HOUR, true));
        assertEquals("12.5/s", Fmt.rate(12.5, RateUnit.SECOND, false));
        assertEquals("0.25/t", Fmt.rate(5, RateUnit.TICK, false));
    }

    @Test
    void machineCountsUseFactoryFlowPrecision() {
        assertEquals("0", Fmt.machines(0));
        assertEquals("<0.001", Fmt.machines(0.0004));
        assertEquals("0.125", Fmt.machines(0.125));
        assertEquals("1", Fmt.machines(1.0));
        assertEquals("2.5", Fmt.machines(2.5));
        assertEquals("12.3", Fmt.machines(12.34));
        assertEquals("101", Fmt.machines(100.2));
    }
}

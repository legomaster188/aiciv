package com.aiciv.actions;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.nav.ColumnGoal;
import com.aiciv.nav.NavStatus;
import com.aiciv.testutil.FakeContext;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ExploreActionTest {
    FakeContext ctx = new FakeContext();

    private ColumnGoal goalFor(String direction, int distance) {
        ExploreAction a = new ExploreAction(direction, distance);
        assertEquals(ActionStatus.RUNNING, a.tick(ctx));
        return assertInstanceOf(ColumnGoal.class, ctx.navigator.goals.get(ctx.navigator.goals.size() - 1));
    }

    @Test
    void northIsNegativeZAndEastIsPositiveX() {
        ctx.position = new Vec3(10.5, 64, 20.5);
        ColumnGoal north = goalFor("N", 30);
        assertEquals(10.5, north.x(), 0.001);
        assertEquals(-9.5, north.z(), 0.001);
        assertEquals(8.0, north.radius());
        ColumnGoal east = goalFor("east", 30);
        assertEquals(40.5, east.x(), 0.001);
        assertEquals(20.5, east.z(), 0.001);
    }

    @Test
    void diagonalsCoverTheRequestedDistanceInTotal() {
        ctx.position = new Vec3(0.5, 64, 0.5);
        ColumnGoal g = goalFor("South-West", 100);
        assertEquals(-70.71, g.x() - 0.5, 0.01);
        assertEquals(70.71, g.z() - 0.5, 0.01);
        ColumnGoal ne = goalFor("ne", 100);
        assertTrue(ne.x() > 0.5 && ne.z() < 0.5);
        ColumnGoal nw = goalFor("north west", 10);
        assertTrue(nw.x() < 0.5 && nw.z() < 0.5);
        ColumnGoal se = goalFor("southeast", 10);
        assertTrue(se.x() > 0.5 && se.z() > 0.5);
    }

    @Test
    void anUnknownDirectionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ExploreAction("up", 10));
        assertFalse(ExploreAction.isDirection("sideways"));
        assertTrue(ExploreAction.isDirection("NorthEast"));
    }

    @Test
    void arrivingFinishesAndNavigationFailureFails() {
        ExploreAction a = new ExploreAction("W", 20);
        a.tick(ctx);
        ctx.navigator.status = NavStatus.ARRIVED;
        assertEquals(ActionStatus.DONE, a.tick(ctx));
        ExploreAction b = new ExploreAction("W", 20);
        b.tick(ctx);
        ctx.navigator.status = NavStatus.failed("no path");
        assertEquals(ActionStatus.failed("no path"), b.tick(ctx));
    }

    @Test
    void timesOutAfterThreeMinutesAndStopsNavigationWhenCancelled() {
        ExploreAction a = new ExploreAction("N", 10);
        assertEquals(3 * 60 * 20, a.timeoutTicks());
        assertEquals("explore N 10", a.describe());
        a.cancel(ctx);
        assertEquals(1, ctx.navigator.stops);
    }
}

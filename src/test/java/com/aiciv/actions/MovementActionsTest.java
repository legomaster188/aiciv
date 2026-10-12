package com.aiciv.actions;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.nav.ColumnGoal;
import com.aiciv.nav.NavStatus;
import com.aiciv.nav.NearGoal;
import com.aiciv.testutil.FakeContext;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class MovementActionsTest {
    FakeContext ctx = new FakeContext();

    @Test
    void goToNearbyUsesANearGoal() {
        GoToAction a = new GoToAction(10, 64, 0);
        assertEquals(ActionStatus.RUNNING, a.tick(ctx));
        NearGoal g = assertInstanceOf(NearGoal.class, ctx.navigator.goals.get(0));
        assertEquals(10.5, g.x());
        assertEquals(1.5, g.radius());
        ctx.navigator.status = NavStatus.ARRIVED;
        assertEquals(ActionStatus.DONE, a.tick(ctx));
    }

    @Test
    void goToFarGoesInLegs() {
        GoToAction a = new GoToAction(1000, 64, 0);
        a.tick(ctx);
        ColumnGoal leg = assertInstanceOf(ColumnGoal.class, ctx.navigator.goals.get(0));
        assertEquals(120.5, leg.x(), 0.01);
        ctx.navigator.status = NavStatus.ARRIVED;
        ctx.position = new Vec3(995.5, 64, 0.5);
        assertEquals(ActionStatus.RUNNING, a.tick(ctx));
        assertInstanceOf(NearGoal.class, ctx.navigator.goals.get(1));
    }

    @Test
    void goToReportsNavigationFailure() {
        GoToAction a = new GoToAction(10, 64, 0);
        a.tick(ctx);
        ctx.navigator.status = NavStatus.failed("no path");
        assertEquals(ActionStatus.failed("no path"), a.tick(ctx));
    }

    @Test
    void goToPlayerFailsWhenOfflineAndFinishesWhenClose() {
        assertEquals(ActionStatus.State.FAILED, new GoToPlayerAction("Steve").tick(ctx).state());
        ctx.players.put("Steve", new Vec3(20.5, 64, 0.5));
        GoToPlayerAction a = new GoToPlayerAction("Steve");
        assertEquals(ActionStatus.RUNNING, a.tick(ctx));
        assertEquals(1, ctx.navigator.goals.size());
        ctx.position = new Vec3(18.5, 64, 0.5);
        assertEquals(ActionStatus.DONE, a.tick(ctx));
    }

    @Test
    void followEndsAfterItsTime() {
        ctx.players.put("Steve", new Vec3(1.5, 64, 0.5));
        FollowAction a = new FollowAction("Steve", 1);
        for (int i = 0; i < 20; i++) assertEquals(ActionStatus.RUNNING, a.tick(ctx));
        assertEquals(ActionStatus.DONE, a.tick(ctx));
        assertEquals((1 + 5) * 20, a.timeoutTicks());
    }

    @Test
    void followChasesWhenFar() {
        ctx.players.put("Steve", new Vec3(30.5, 64, 0.5));
        FollowAction a = new FollowAction("Steve", 10);
        a.tick(ctx);
        assertEquals(1, ctx.navigator.goals.size());
        ctx.players.put("Steve", new Vec3(40.5, 64, 0.5));
        a.tick(ctx);
        assertEquals(2, ctx.navigator.goals.size());
    }

    @Test
    void goToPlayerStopsWhenTheNavigatorArrivesJustOutOfRange() {
        ctx.players.put("Steve", new Vec3(20.5, 64, 0.5));
        GoToPlayerAction a = new GoToPlayerAction("Steve");
        assertEquals(ActionStatus.RUNNING, a.tick(ctx));
        ctx.position = new Vec3(17.1, 64, 0.5);
        ctx.navigator.status = NavStatus.ARRIVED;
        assertEquals(ActionStatus.DONE, a.tick(ctx));
        assertEquals(1, ctx.navigator.goals.size());
        assertEquals(1, ctx.navigator.stops);
    }

    @Test
    void followDoesNotReissueWhenTheNavigatorArrivesJustOutOfRange() {
        ctx.players.put("Steve", new Vec3(20.5, 64, 0.5));
        FollowAction a = new FollowAction("Steve", 10);
        assertEquals(ActionStatus.RUNNING, a.tick(ctx));
        assertEquals(1, ctx.navigator.goals.size());
        ctx.position = new Vec3(17.1, 64, 0.5);
        ctx.navigator.status = NavStatus.ARRIVED;
        assertEquals(ActionStatus.RUNNING, a.tick(ctx));
        assertEquals(1, ctx.navigator.goals.size());
    }

    @Test
    void goToPlayerKeepsWalkingWhenThePlayerWandersAndTheBotIsStillFar() {
        ctx.players.put("Steve", new Vec3(20.5, 64, 0.5));
        GoToPlayerAction a = new GoToPlayerAction("Steve");
        assertEquals(ActionStatus.RUNNING, a.tick(ctx));
        ctx.players.put("Steve", new Vec3(23.4, 64, 0.5));
        ctx.position = new Vec3(18.0, 64, 0.5);
        ctx.navigator.status = NavStatus.ARRIVED;
        assertEquals(ActionStatus.RUNNING, a.tick(ctx));
        assertEquals(2, ctx.navigator.goals.size());
        NearGoal again = assertInstanceOf(NearGoal.class, ctx.navigator.goals.get(1));
        assertEquals(23.4, again.x());
    }

    @Test
    void sayTalksOnce() {
        assertEquals(ActionStatus.DONE, new SayAction("hello").tick(ctx));
        assertEquals(java.util.List.of("hello"), ctx.said);
    }
}

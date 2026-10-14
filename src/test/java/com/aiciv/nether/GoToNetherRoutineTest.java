package com.aiciv.nether;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aiciv.actions.ActionStatus;
import com.aiciv.testutil.FakeContext;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class GoToNetherRoutineTest {
    private static ActionStatus firstTick(String dimension) {
        FakeContext ctx = new FakeContext();
        ctx.dimension = dimension;
        GoToNetherRoutine routine = new GoToNetherRoutine();
        ActionStatus s = routine.tick(ctx);
        return s.state() == ActionStatus.State.RUNNING ? routine.tick(ctx) : s;
    }

    @Test
    void refusesInTheEnd() {
        ActionStatus s = firstTick("minecraft:the_end");
        assertEquals(ActionStatus.State.FAILED, s.state());
        assertEquals("go_to_nether: nether portals can't be lit in the End", s.reason());
    }

    @Test
    void portalNamesCarryDimensionAndHeight() {
        assertEquals("portal_the_nether_m3_70_12", BuildPortalAction.structureName("minecraft:the_nether", new BlockPos(-3, 70, 12)));
        assertEquals("portal_overworld_m3_64_12", BuildPortalAction.structureName("minecraft:overworld", new BlockPos(-3, 64, 12)));
    }

    @Test
    void refusesInTheNether() {
        assertEquals("go_to_nether: you are already in the_nether", firstTick("minecraft:the_nether").reason());
    }
}

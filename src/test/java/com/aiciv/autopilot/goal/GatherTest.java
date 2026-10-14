package com.aiciv.autopilot.goal;

import static com.aiciv.autopilot.Sit.sit;
import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.llm.ToolCall;
import org.junit.jupiter.api.Test;

class GatherTest {
    private static ToolCall only(Move m) {
        assertEquals(1, m.calls().size(), m.why());
        return m.calls().get(0);
    }

    @Test
    void logsAreMinedThreeAtLeast() {
        ToolCall c = only(Gather.move("log", 1, sit().build()));
        assertEquals("mine", c.name());
        assertEquals("log", c.args().get("block").getAsString());
        assertEquals(3, c.args().get("count").getAsInt());
        assertEquals(5, only(Gather.move("log", 5, sit().build())).args().get("count").getAsInt());
    }

    @Test
    void cobblestoneNeedsAPickaxe() {
        Move m = Gather.move("cobblestone", 3, sit().build());
        assertEquals(Move.Kind.BLOCKED, m.kind());
        assertEquals("needs a pickaxe for cobblestone", m.why());
        ToolCall c = only(Gather.move("cobblestone", 3, sit().inv("wooden_pickaxe", 1).build()));
        assertEquals("stone", c.args().get("block").getAsString());
    }

    @Test
    void ironIngotsSmeltWhenRawIronFuelAndFurnaceAreThere() {
        var s = sit().inv("stone_pickaxe", 1).inv("raw_iron", 3).inv("coal", 1).block("furnace", 4).build();
        ToolCall c = only(Gather.move("iron_ingot", 3, s));
        assertEquals("smelt", c.name());
        assertEquals("raw_iron", c.args().get("item").getAsString());
        assertEquals(3, c.args().get("count").getAsInt());
    }

    @Test
    void ironIngotsMineIronFirst() {
        ToolCall c = only(Gather.move("iron_ingot", 3, sit().inv("stone_pickaxe", 1).build()));
        assertEquals("mine", c.name());
        assertEquals("iron_ore", c.args().get("block").getAsString());
        assertEquals(3, c.args().get("count").getAsInt());
        assertEquals("needs a stone pickaxe for iron", Gather.move("iron_ingot", 3, sit().inv("wooden_pickaxe", 1).build()).why());
    }

    @Test
    void ironIngotsNeedFuelThenAFurnace() {
        var base = sit().inv("stone_pickaxe", 1).inv("raw_iron", 3).block("furnace", 4);
        ToolCall coal = only(Gather.move("iron_ingot", 3, base.build()));
        assertEquals("coal_ore", coal.args().get("block").getAsString());
        assertEquals(1, coal.args().get("count").getAsInt());

        var noFurnace = sit().inv("stone_pickaxe", 1).inv("raw_iron", 3).inv("coal", 1).inv("cobblestone", 2).build();
        ToolCall stone = only(Gather.move("iron_ingot", 3, noFurnace));
        assertEquals("stone", stone.args().get("block").getAsString());
        assertEquals(6, stone.args().get("count").getAsInt());

        var logsAsFuel = sit().inv("stone_pickaxe", 1).inv("raw_iron", 3).inv("oak_log", 3).block("furnace", 4).build();
        assertEquals("smelt", only(Gather.move("iron_ingot", 3, logsAsFuel)).name());
    }

    @Test
    void diamondsDigDownFirstThenMine() {
        var high = sit().at(10, 70, 20).inv("iron_pickaxe", 1).build();
        ToolCall go = only(Gather.move("diamond", 3, high));
        assertEquals("goTo", go.name());
        assertEquals(30, go.args().get("y").getAsInt());
        assertEquals(18, go.args().get("x").getAsInt());
        assertEquals(20, go.args().get("z").getAsInt());

        var low = sit().at(0, 70, 0).inv("iron_pickaxe", 1).remember("diamond_ore at 3 -50 4").build();
        assertEquals("diamond_ore", only(Gather.move("diamond", 3, low)).args().get("block").getAsString());
        assertEquals("needs an iron pickaxe for diamonds", Gather.move("diamond", 3, sit().inv("stone_pickaxe", 1).build()).why());
    }

    @Test
    void mobDropsAndUnknownMaterials() {
        Move rods = Gather.move("blaze_rod", 6, sit().build());
        assertEquals("attack", rods.calls().get(0).name());
        assertEquals("collect", rods.calls().get(1).name());
        assertEquals("explore", only(Gather.move("wool", 3, sit().build())).name());
        assertEquals("attack", Gather.move("wool", 3, sit().mob("sheep", 2, 10, "N").build()).calls().get(0).name());
        Move m = Gather.move("unobtainium", 1, sit().build());
        assertEquals(Move.Kind.BLOCKED, m.kind());
        assertEquals("don't know how to get unobtainium", m.why());
    }
}

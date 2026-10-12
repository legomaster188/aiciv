package com.aiciv.brain;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.brain.OptionMenu.MenuContext;
import com.aiciv.perception.SnapshotData;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OptionMenuTest {
    static SnapshotData snap(String equipment) {
        return new SnapshotData("Ada", 0, 64, 0, 20, 20, 20, 1, "morning", "plains", "nothing", equipment,
                List.of(), List.of(), List.of(), List.of(), List.of(), "none", "nothing yet");
    }

    static MenuContext ctx(Map<String, Integer> inventory, List<String> craftable, boolean hungry,
                           List<String> mobs, List<String> players, List<String> mineable) {
        return new MenuContext(inventory, craftable, hungry, mobs, players, mineable);
    }

    static MenuContext empty() {
        return ctx(Map.of(), List.of(), false, List.of(), List.of(), List.of());
    }

    static Set<String> ids(List<MenuOption> options) {
        Set<String> ids = new HashSet<>();
        options.forEach(o -> ids.add(o.id()));
        return ids;
    }

    @Test
    void alwaysHasWaitAndFourExploreDirections() {
        Set<String> ids = ids(OptionMenu.build(snap("none"), empty()));
        assertTrue(ids.containsAll(List.of("wait", "explore_n", "explore_e", "explore_s", "explore_w", "say_hello", "say_help")), ids.toString());
    }

    @Test
    void eatOnlyWhenHungryWithFood() {
        Map<String, Integer> bread = Map.of("bread", 2);
        assertTrue(ids(OptionMenu.build(snap("none"), ctx(bread, List.of(), true, List.of(), List.of(), List.of()))).contains("eat"));
        assertFalse(ids(OptionMenu.build(snap("none"), ctx(bread, List.of(), false, List.of(), List.of(), List.of()))).contains("eat"));
        assertFalse(ids(OptionMenu.build(snap("none"), ctx(Map.of("dirt", 3), List.of(), true, List.of(), List.of(), List.of()))).contains("eat"));
    }

    @Test
    void aZombieOffersAnAttack() {
        List<MenuOption> options = OptionMenu.build(snap("none"), ctx(Map.of(), List.of(), false, List.of("zombie"), List.of(), List.of()));
        MenuOption attack = options.stream().filter(o -> o.id().equals("attack_zombie")).findFirst().orElseThrow();
        assertEquals("attack", attack.calls().get(0).name());
        assertEquals("zombie", attack.calls().get(0).args().get("target").getAsString());
    }

    @Test
    void craftableTargetsBecomeCraftOptions() {
        List<MenuOption> options = OptionMenu.build(snap("none"),
                ctx(Map.of("oak_log", 3), List.of("wooden_pickaxe"), false, List.of(), List.of(), List.of()));
        MenuOption craft = options.stream().filter(o -> o.id().equals("craft_wooden_pickaxe")).findFirst().orElseThrow();
        assertEquals("craft", craft.calls().get(0).name());
        assertEquals("wooden_pickaxe", craft.calls().get(0).args().get("item").getAsString());
    }

    @Test
    void mineCountsFollowTheBlockKind() {
        List<MenuOption> options = OptionMenu.build(snap("none"),
                ctx(Map.of(), List.of(), false, List.of(), List.of(), List.of("oak_log", "stone", "iron_ore", "crafting_table")));
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (MenuOption o : options) {
            if (o.id().startsWith("mine_")) counts.put(o.id(), o.calls().get(0).args().get("count").getAsInt());
        }
        assertEquals(Map.of("mine_oak_log", 6, "mine_stone", 8, "mine_iron_ore", 3), counts);
    }

    @Test
    void playersGetGoToAndFollowAndRawIronGetsSmelt() {
        List<MenuOption> options = OptionMenu.build(snap("none"),
                ctx(Map.of("raw_iron", 4), List.of(), false, List.of(), List.of("Steve"), List.of()));
        Set<String> ids = ids(options);
        assertTrue(ids.containsAll(List.of("go_to_steve", "follow_steve", "smelt_raw_iron")), ids.toString());
        MenuOption go = options.stream().filter(o -> o.id().equals("go_to_steve")).findFirst().orElseThrow();
        assertEquals("Steve", go.calls().get(0).args().get("name").getAsString());
    }

    @Test
    void unwornArmorCanBeEquippedButWornArmorCannot() {
        Map<String, Integer> inv = Map.of("iron_helmet", 1, "iron_boots", 1);
        Set<String> ids = ids(OptionMenu.build(snap("iron_boots"), ctx(inv, List.of(), false, List.of(), List.of(), List.of())));
        assertTrue(ids.contains("equip_iron_helmet"));
        assertFalse(ids.contains("equip_iron_boots"));
    }

    @Test
    void idsAreUniqueValidAndCappedAtForty() {
        List<String> mobs = new ArrayList<>(), players = new ArrayList<>(), mineable = new ArrayList<>(), craftable = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            mobs.add("mob" + i);
            players.add("Player" + i);
            mineable.add("wood" + i + "_log");
            craftable.add("item" + i);
        }
        mobs.add("zombie");
        mobs.add("zombie");
        List<MenuOption> options = OptionMenu.build(snap("none"), ctx(Map.of("bread", 1), craftable, true, mobs, players, mineable));
        assertEquals(40, options.size());
        Set<String> seen = new HashSet<>();
        for (MenuOption o : options) {
            assertTrue(OptionMenu.ID.matcher(o.id()).matches(), o.id());
            assertTrue(seen.add(o.id()), "duplicate " + o.id());
            assertTrue(o.calls().size() <= 3);
        }
        assertTrue(seen.containsAll(List.of("wait", "explore_n", "explore_e", "explore_s", "explore_w")));
        assertTrue(seen.contains("eat"), "the highest priority option is kept");
    }
}

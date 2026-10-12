package com.aiciv.storage;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DepositRuleTest {
    @Test
    void threeSpellingsOfExceptTools() {
        for (String s : new String[] {"all_except_tools", "all except tools", "everything but tools", " ALL_EXCEPT_TOOLS "}) {
            assertSame(DepositRule.ALL_EXCEPT_TOOLS, DepositRule.parse(s), s);
        }
        assertSame(DepositRule.ALL, DepositRule.parse("all"));
    }

    @Test
    void listCarriesLimits() {
        DepositRule rule = DepositRule.parse("cobblestone x20, oak_log, dirt 5");
        assertEquals(20, rule.limit("cobblestone"));
        assertEquals(Integer.MAX_VALUE, rule.limit("oak_log"));
        assertEquals(5, rule.limit("dirt"));
        assertFalse(rule.keeps("oak_log", false));
        assertTrue(rule.keeps("stone", false));
    }

    @Test
    void garbageThrowsWithTheQuotedMessage() {
        for (String s : new String[] {"???", "", "cobblestone x0", ",,", "dirt x"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> DepositRule.parse(s), s);
            assertEquals("deposit \"items\" must be all, all_except_tools, or a list like \"cobblestone x20, oak_log\"", e.getMessage());
        }
    }

    @Test
    void exceptToolsKeepsToolsWeaponsArmorAndFood() {
        DepositRule rule = DepositRule.ALL_EXCEPT_TOOLS;
        assertTrue(rule.keeps("stone_pickaxe", true));
        assertTrue(rule.keeps("bread", true)); // the action passes true for food too
        assertFalse(rule.keeps("cobblestone", false));
        assertFalse(DepositRule.ALL.keeps("stone_pickaxe", true));
        assertEquals(Integer.MAX_VALUE, rule.limit("anything"));
    }
}

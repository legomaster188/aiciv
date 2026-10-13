package com.aiciv.panel;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PanelInputTest {
    @Test
    void namesFollowTheSpawnRule() {
        assertNull(PanelInput.checkName("Ada_01"));
        assertNull(PanelInput.checkName("abc"));
        assertNull(PanelInput.checkName("a".repeat(16)));
        assertNotNull(PanelInput.checkName(null));
        assertNotNull(PanelInput.checkName("ab"));
        assertNotNull(PanelInput.checkName("a".repeat(17)));
        assertNotNull(PanelInput.checkName("../etc"));
        assertNotNull(PanelInput.checkName("a b c"));
        assertNotNull(PanelInput.checkName("name\n"));
    }

    @Test
    void targetsAlsoAllowAll() {
        assertNull(PanelInput.checkTarget("all"));
        assertNull(PanelInput.checkTarget("ALL"));
        assertNull(PanelInput.checkTarget("Ada"));
        assertNotNull(PanelInput.checkTarget("x"));
    }

    @Test
    void modelsMustBeConfigured() {
        Set<String> known = Set.of("gemini-flash", "jev");
        assertNull(PanelInput.checkModel("jev", known));
        assertTrue(PanelInput.checkModel("nope", known).contains("nope"));
        assertNotNull(PanelInput.checkModel(null, known));
        assertNotNull(PanelInput.checkModel(" ", known));
    }

    @Test
    void textIsBoundedAndRequiredOnlyWhenAsked() {
        assertNull(PanelInput.checkText("goal", "", 10, false));
        assertNull(PanelInput.checkText("goal", null, 10, false));
        assertEquals("text is required", PanelInput.checkText("text", " ", 10, true));
        assertNull(PanelInput.checkText("goal", "x".repeat(10), 10, true));
        assertTrue(PanelInput.checkText("goal", "x".repeat(11), 10, true).contains("10"));
    }

    @Test
    void stringReadsOnlyJsonStrings() {
        JsonObject o = JsonParser.parseString("{\"a\": \"x\", \"b\": 5, \"c\": {\"d\": 1}, \"e\": null}").getAsJsonObject();
        assertEquals("x", PanelInput.string(o, "a"));
        assertNull(PanelInput.string(o, "b"));
        assertNull(PanelInput.string(o, "c"));
        assertNull(PanelInput.string(o, "e"));
        assertNull(PanelInput.string(o, "missing"));
    }
}

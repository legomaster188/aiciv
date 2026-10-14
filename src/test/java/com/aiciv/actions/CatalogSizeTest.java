package com.aiciv.actions;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import org.junit.jupiter.api.Test;

class CatalogSizeTest {
    @Test
    void fullCatalogStaysUnderTheBudget() {
        JsonArray tools = ToolCatalog.tools();
        int chars = tools.toString().length();
        int guide = ToolCatalog.guide().length();
        System.out.println("[CatalogSizeTest] " + tools.size() + " tools, " + chars + " chars (~" + chars / 4 + " tokens), guide " + guide + " chars");
        assertTrue(chars < 60_000, "the tool catalog is " + chars + " characters");
    }
}

package com.aiciv.stations;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class StationNamesTest {
    @Test
    void trimTemplateAcceptsTheCommonSpellings() {
        String id = "coast_armor_trim_smithing_template";
        assertEquals(Optional.of(id), StationNames.trimTemplate("coast"));
        assertEquals(Optional.of(id), StationNames.trimTemplate("Coast Trim"));
        assertEquals(Optional.of(id), StationNames.trimTemplate(id));
        assertEquals(Optional.of(id), StationNames.trimTemplate("minecraft:" + id));
        assertEquals(Optional.empty(), StationNames.trimTemplate("sparkle"));
        assertEquals(Optional.empty(), StationNames.trimTemplate(null));
    }

    @Test
    void trimMaterialsMatchByFullOrShortName() {
        assertEquals(Optional.of("iron_ingot"), StationNames.trimMaterial("iron"));
        assertEquals(Optional.of("iron_ingot"), StationNames.trimMaterial("iron ingot"));
        assertEquals(Optional.of("lapis_lazuli"), StationNames.trimMaterial("lapis"));
        assertEquals(Optional.of("amethyst_shard"), StationNames.trimMaterial("amethyst"));
        assertEquals(Optional.of("diamond"), StationNames.trimMaterial("diamond"));
        assertEquals(Optional.empty(), StationNames.trimMaterial("dirt"));
    }

    @Test
    void netheriteResultCoversDiamondToolsAndArmorOnly() {
        assertEquals(Optional.of("netherite_sword"), StationNames.netheriteResult("diamond_sword"));
        assertEquals(Optional.of("netherite_hoe"), StationNames.netheriteResult("diamond_hoe"));
        assertEquals(Optional.of("netherite_boots"), StationNames.netheriteResult("Diamond Boots"));
        assertEquals(Optional.empty(), StationNames.netheriteResult("iron_sword"));
        assertEquals(Optional.empty(), StationNames.netheriteResult("diamond_horse_armor"));
        assertEquals(Optional.empty(), StationNames.netheriteResult("diamond"));
    }

    @Test
    void patternKeysUseRegistryNames() {
        assertEquals("stripe_bottom", StationNames.patternKey("bottom stripe"));
        assertEquals("stripe_top", StationNames.patternKey("Top Stripe"));
        assertEquals("stripe_bottom", StationNames.patternKey("stripe_bottom"));
        assertEquals("cross", StationNames.patternKey("cross"));
        assertEquals("small_stripes", StationNames.patternKey("small stripes"));
        assertEquals("creeper", StationNames.patternKey("Creeper"));
        assertEquals("mojang", StationNames.patternKey("mojang"));
    }

    @Test
    void pagesSplitLongTextAtSpaces() {
        assertEquals(java.util.List.of("short"), StationNames.pages("short", 1024));
        String text = ("word ".repeat(300)).trim(); // 1499 chars
        java.util.List<String> pages = StationNames.pages(text, 1024);
        assertEquals(2, pages.size());
        assertTrue(pages.get(0).length() <= 1024);
        assertEquals(text, String.join("", pages));
        assertEquals(3, StationNames.pages("x".repeat(2100), 1024).size());
    }
}

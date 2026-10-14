package com.aiciv.dimension;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PortalsTest {
    @Test
    void shortNameDropsTheNamespace() {
        assertEquals("the_nether", Portals.shortName("minecraft:the_nether"));
        assertEquals("overworld", Portals.shortName("overworld"));
    }

    @Test
    void unknownKindsHaveNoPredicate() {
        assertNull(Portals.ofKind("lava"));
        assertEquals("the_end does not exist on this server", String.format(Portals.NO_LEVEL, "the_end"));
    }
}

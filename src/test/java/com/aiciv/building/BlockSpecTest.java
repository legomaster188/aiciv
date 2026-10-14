package com.aiciv.building;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class BlockSpecTest {
    @Test
    void parsesPropertiesWithSpaces() throws Exception {
        BlockSpec spec = BlockSpec.parse("repeater[facing=north, delay=2]");
        assertEquals("repeater", spec.id());
        assertEquals(Map.of("facing", "north", "delay", "2"), spec.props());
    }

    @Test
    void normalisesNamespaceAndCase() throws Exception {
        BlockSpec spec = BlockSpec.parse("minecraft:Lever[Face=Wall]");
        assertEquals("lever", spec.id());
        assertEquals(Map.of("face", "wall"), spec.props());
    }

    @Test
    void anUnclosedBracketIsNamed() {
        assertEquals("legend value \"repeater[facing\" has an unclosed [", assertThrows(BlueprintException.class, () -> BlockSpec.parse("repeater[facing")).getMessage());
    }

    @Test
    void aPropertyWithoutAValueIsNamed() {
        assertEquals("property \"facing\" needs a value", assertThrows(BlueprintException.class, () -> BlockSpec.parse("repeater[facing]")).getMessage());
        assertEquals("property \"delay\" needs a value", assertThrows(BlueprintException.class, () -> BlockSpec.parse("repeater[delay=]")).getMessage());
    }

    @Test
    void aPlainIdHasNoProperties() throws Exception {
        BlockSpec spec = BlockSpec.parse("stone");
        assertEquals("stone", spec.id());
        assertTrue(spec.props().isEmpty());
        assertEquals("stone", spec.text());
    }

    @Test
    void textIsCanonicalWithSortedKeys() throws Exception {
        assertEquals("repeater[delay=2,facing=north]", BlockSpec.parse("Repeater[ facing = north ,delay=2]").text());
    }
}

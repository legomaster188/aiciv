package com.aiciv.building;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class BlueprintTest {
    private static JsonElement json(String s) {
        return JsonParser.parseString(s);
    }

    private static String reason(String layers, String legend) {
        return assertThrows(BlueprintException.class, () -> Blueprint.parse(json(layers), json(legend))).getMessage();
    }

    @Test
    void raggedRowsPadWithSpaces() throws Exception {
        Blueprint bp = Blueprint.parse(json("[[\"ab\",\"a\"]]"), json("{\"a\":\"stone\",\"b\":\"dirt\"}"));
        assertEquals(2, bp.width());
        assertEquals(2, bp.depth());
        assertEquals(1, bp.height());
        assertEquals(' ', bp.cells()[0][1][1]);
        assertEquals('b', bp.cells()[0][0][1]);
    }

    @Test
    void aLayerAsOneSlashStringEqualsTheArrayForm() throws Exception {
        String legend = "{\"c\":\"cobblestone\"}";
        Blueprint a = Blueprint.parse(json("[\"ccc/c.c/ccc\"]"), json(legend));
        Blueprint b = Blueprint.parse(json("[[\"ccc\",\"c.c\",\"ccc\"]]"), json(legend));
        assertTrue(Arrays.deepEquals(a.cells(), b.cells()));
        assertEquals(a, b);
        assertEquals(3, a.width());
        assertEquals(3, a.depth());
    }

    @Test
    void newlinesSeparateRowsToo() throws Exception {
        Blueprint bp = Blueprint.parse(json("[\"cc\\nc.\"]"), json("{\"c\":\"stone\"}"));
        assertEquals(2, bp.depth());
    }

    @Test
    void unknownSymbolNamesLayerAndRowOneBased() {
        String r = reason("[[\"cc\"],[\"cc\",\"cc\",\"cx\"]]", "{\"c\":\"stone\"}");
        assertEquals("unknown symbol 'x' in layer 2 row 3 (add it to the legend)", r);
    }

    @Test
    void tooLargeNamesTheLimit() {
        StringBuilder rows = new StringBuilder("[[");
        for (int i = 0; i < 17; i++) rows.append(i > 0 ? "," : "").append("\"c\"");
        rows.append("]]");
        String r = reason(rows.toString(), "{\"c\":\"stone\"}");
        assertTrue(r.startsWith("blueprint is 1x17x1; the limit is 16x16x16"), r);
    }

    @Test
    void legendValuesAreNormalised() throws Exception {
        Blueprint bp = Blueprint.parse(json("[[\"p\"]]"), json("{\"p\":\"minecraft:Oak Planks\"}"));
        assertEquals("oak_planks", bp.legend().get('p'));
    }

    @Test
    void reservedAndLongLegendKeysAreRejected() {
        assertTrue(reason("[[\"c\"]]", "{\".\":\"stone\",\"c\":\"stone\"}").startsWith("legend key '.'"));
        assertTrue(reason("[[\"c\"]]", "{\" \":\"stone\",\"c\":\"stone\"}").startsWith("legend key ' '"));
        assertEquals("legend key 'ab' must be one character", reason("[[\"a\"]]", "{\"ab\":\"stone\"}"));
    }

    @Test
    void emptyOrMalformedLayersAreRejected() {
        assertTrue(reason("[]", "{\"c\":\"stone\"}").startsWith("layers must be a non-empty list"));
        assertTrue(reason("\"c\"", "{\"c\":\"stone\"}").startsWith("layers must be a non-empty list"));
    }

    @Test
    void aBlueprintOfOnlyAirPlacesNothing() {
        assertTrue(reason("[[\"..\",\"..\"]]", "{\"c\":\"stone\"}").startsWith("the blueprint places nothing"));
    }

    @Test
    void placeCountCountsLegendCells() throws Exception {
        Blueprint bp = Blueprint.parse(json("[[\"c.c\",\" c \"]]"), json("{\"c\":\"stone\"}"));
        assertEquals(3, bp.placeCount());
    }

    @Test
    void legendValuesMayCarryBlockStates() throws Exception {
        Blueprint bp = Blueprint.parse(json("[[\"R\"]]"), json("{\"R\":\"repeater[facing=north,delay=2]\"}"));
        assertEquals(java.util.Map.of('R', "repeater"), bp.legend());
        assertEquals(java.util.Map.of('R', java.util.Map.of("facing", "north", "delay", "2")), bp.states());
        assertTrue(Blueprint.parse(json("[[\"c\"]]"), json("{\"c\":\"stone\"}")).states().isEmpty());
    }

    @Test
    void aBadStateInTheLegendIsReported() {
        assertEquals("legend value \"repeater[facing\" has an unclosed [", reason("[[\"R\"]]", "{\"R\":\"repeater[facing\"}"));
    }

    @Test
    void aJsonArrayOfPlainStringsIsOneLayerPerString() throws Exception {
        JsonArray layers = new JsonArray();
        layers.add("c");
        layers.add("c");
        assertEquals(2, Blueprint.parse(layers, json("{\"c\":\"stone\"}")).height());
    }
}

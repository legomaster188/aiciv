package com.aiciv.building;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A structure as a model designs it: {@code cells[y][row][col]}, layers bottom to top. {@code '.'} must be air,
 * {@code ' '} is left as it is, any other symbol is a key of {@code legend} (block id without namespace).
 */
public record Blueprint(int width, int depth, int height, char[][][] cells, Map<Character, String> legend, int yOffset,
                        Map<Character, Map<String, String>> states) {
    public static final int LIMIT = 16;

    /** A blueprint with no block states. */
    public Blueprint(int width, int depth, int height, char[][][] cells, Map<Character, String> legend, int yOffset) {
        this(width, depth, height, cells, legend, yOffset, Map.of());
    }

    /**
     * @param layers array (bottom to top) of layers; a layer is an array of row strings or one string with rows
     *               separated by '/' or a newline
     * @param legend object of one-character keys to block ids
     */
    public static Blueprint parse(JsonElement layers, JsonElement legend) throws BlueprintException {
        if (layers == null || !layers.isJsonArray() || layers.getAsJsonArray().isEmpty()) {
            throw new BlueprintException("layers must be a non-empty list of layers, bottom to top");
        }
        Map<Character, Map<String, String>> states = new LinkedHashMap<>();
        Map<Character, String> blocks = parseLegend(legend, states);
        List<List<String>> rowsByLayer = new ArrayList<>();
        int layerNumber = 0;
        for (JsonElement layer : layers.getAsJsonArray()) {
            layerNumber++;
            List<String> rows = new ArrayList<>();
            if (layer.isJsonArray()) {
                int rowNumber = 0;
                for (JsonElement row : layer.getAsJsonArray()) {
                    rowNumber++;
                    if (!isString(row)) throw new BlueprintException("layer " + layerNumber + " row " + rowNumber + " must be a string");
                    rows.add(row.getAsString());
                }
            } else if (isString(layer)) {
                for (String row : layer.getAsString().replace("\r", "").split("[/\n]", -1)) rows.add(row);
            } else {
                throw new BlueprintException("layer " + layerNumber + " must be a list of row strings or one string with rows separated by /");
            }
            if (rows.isEmpty()) throw new BlueprintException("layer " + layerNumber + " has no rows");
            rowsByLayer.add(rows);
        }
        int width = 0, depth = 0;
        for (List<String> rows : rowsByLayer) {
            depth = Math.max(depth, rows.size());
            for (String row : rows) width = Math.max(width, row.length());
        }
        int height = rowsByLayer.size();
        if (width > LIMIT || depth > LIMIT || height > LIMIT) {
            throw new BlueprintException("blueprint is " + width + "x" + depth + "x" + height + "; the limit is " + LIMIT + "x" + LIMIT + "x" + LIMIT);
        }
        char[][][] cells = new char[height][depth][width];
        int placed = 0;
        for (int y = 0; y < height; y++) {
            List<String> rows = rowsByLayer.get(y);
            for (int r = 0; r < depth; r++) {
                Arrays.fill(cells[y][r], ' ');
                if (r >= rows.size()) continue;
                String row = rows.get(r);
                for (int c = 0; c < row.length(); c++) {
                    char symbol = row.charAt(c);
                    cells[y][r][c] = symbol;
                    if (symbol == '.' || symbol == ' ') continue;
                    if (!blocks.containsKey(symbol)) {
                        throw new BlueprintException("unknown symbol '" + symbol + "' in layer " + (y + 1) + " row " + (r + 1) + " (add it to the legend)");
                    }
                    placed++;
                }
            }
        }
        if (placed == 0) throw new BlueprintException("the blueprint places nothing: it needs at least one legend symbol");
        return new Blueprint(width, depth, height, cells, blocks, 0, states);
    }

    private static boolean isString(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
    }

    private static Map<Character, String> parseLegend(JsonElement legend, Map<Character, Map<String, String>> states) throws BlueprintException {
        if (legend == null || !legend.isJsonObject()) throw new BlueprintException("legend must be an object mapping one-character symbols to block ids");
        JsonObject object = legend.getAsJsonObject();
        Map<Character, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : object.entrySet()) {
            String key = e.getKey();
            if (key.length() != 1) throw new BlueprintException("legend key '" + key + "' must be one character");
            char symbol = key.charAt(0);
            if (symbol == '.' || symbol == ' ') {
                throw new BlueprintException("legend key '" + symbol + "' is reserved: '.' means air and ' ' means leave the cell alone");
            }
            if (!isString(e.getValue())) throw new BlueprintException("legend value for '" + key + "' must be a block id such as cobblestone");
            BlockSpec spec = BlockSpec.parse(e.getValue().getAsString());
            if (spec.id().isEmpty()) throw new BlueprintException("legend value for '" + key + "' is empty");
            out.put(symbol, spec.id());
            if (!spec.props().isEmpty()) states.put(symbol, spec.props());
        }
        return out;
    }

    public Blueprint withYOffset(int offset) {
        return new Blueprint(width, depth, height, cells, legend, offset, states);
    }

    /** The symbol at the cell; a space outside the blueprint. */
    public char at(int y, int row, int col) {
        if (y < 0 || y >= height || row < 0 || row >= depth || col < 0 || col >= width) return ' ';
        return cells[y][row][col];
    }

    /** How many cells name a block. */
    public int placeCount() {
        int n = 0;
        for (char[][] layer : cells) for (char[] row : layer) for (char c : row) if (legend.containsKey(c)) n++;
        return n;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Blueprint b && width == b.width && depth == b.depth && height == b.height && yOffset == b.yOffset
                && Arrays.deepEquals(cells, b.cells) && legend.equals(b.legend) && states.equals(b.states);
    }

    @Override
    public int hashCode() {
        return Objects.hash(width, depth, height, Arrays.deepHashCode(cells), legend, yOffset, states);
    }

    @Override
    public String toString() {
        return "Blueprint[" + width + "x" + depth + "x" + height + ", legend=" + legend + (states.isEmpty() ? "" : ", states=" + states) + ", yOffset=" + yOffset + "]";
    }

    /** Layers of row strings, for tests and the templates. */
    static Blueprint of(List<List<String>> layers, Map<Character, String> legend, int yOffset) {
        int height = layers.size(), depth = 0, width = 0;
        for (List<String> rows : layers) {
            depth = Math.max(depth, rows.size());
            for (String row : rows) width = Math.max(width, row.length());
        }
        char[][][] cells = new char[height][depth][width];
        for (int y = 0; y < height; y++) {
            for (int r = 0; r < depth; r++) {
                Arrays.fill(cells[y][r], ' ');
                if (r < layers.get(y).size()) {
                    String row = layers.get(y).get(r);
                    for (int c = 0; c < row.length(); c++) cells[y][r][c] = row.charAt(c);
                }
            }
        }
        return new Blueprint(width, depth, height, cells, legend, yOffset);
    }
}

package com.aiciv.perception;

import java.util.List;

/**
 * One extra snapshot line from a module, rendered "Label: items". {@code tags} are machine-readable tokens for menus
 * (e.g. "structure:ada_hut", "crop_ripe"). With no items and a null {@code emptyWord} the line is omitted.
 */
public record SnapshotSection(String label, List<String> items, String emptyWord, List<String> tags) {}

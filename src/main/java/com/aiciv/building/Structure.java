package com.aiciv.building;

import com.aiciv.nav.Pos;

/** A finished build, remembered by name so every bot can find it. {@code min}/{@code max} are its bounding box. */
public record Structure(String name, String owner, String dimension, Pos origin, Pos min, Pos max, String kind, long builtTick) {}

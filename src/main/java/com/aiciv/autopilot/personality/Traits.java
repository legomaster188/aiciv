package com.aiciv.autopilot.personality;

import java.util.LinkedHashMap;
import java.util.Map;

/** Five traits in [-1, 1] that shape how an autopilot plays and talks. */
public record Traits(double brave, double greedy, double social, double tidy, double patient) {
    public static final Traits NEUTRAL = new Traits(0, 0, 0, 0, 0);

    public Traits {
        brave = clamp(brave);
        greedy = clamp(greedy);
        social = clamp(social);
        tidy = clamp(tidy);
        patient = clamp(patient);
    }

    /** Clamped to [-1, 1]; NaN becomes 0. */
    public static double clamp(double v) {
        return Double.isNaN(v) ? 0 : Math.max(-1, Math.min(1, v));
    }

    /** brave, greedy, social, tidy, patient, in that order. */
    public Map<String, Double> asMap() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("brave", brave);
        m.put("greedy", greedy);
        m.put("social", social);
        m.put("tidy", tidy);
        m.put("patient", patient);
        return m;
    }
}

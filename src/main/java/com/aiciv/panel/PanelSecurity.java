package com.aiciv.panel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;

/** The panel's request checks, as pure functions. The token is the only credential; Origin and Host stop CSRF and DNS rebinding. */
public final class PanelSecurity {
    public static final String TOKEN_HEADER = "X-AiCiv-Token";

    private PanelSecurity() {}

    /** A fresh 128-bit random token as 32 hex characters. */
    public static String newToken() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** Constant-time comparison; a missing header never matches. */
    public static boolean tokenOk(String expected, String presented) {
        if (expected == null || presented == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }

    /** No Origin header is fine (same-origin GETs and non-browser clients); a present one must be this panel's own page. */
    public static boolean originOk(String origin, int port) {
        if (origin == null) return true;
        String o = origin.toLowerCase(Locale.ROOT);
        return o.equals("http://127.0.0.1:" + port) || o.equals("http://localhost:" + port);
    }

    /** The Host header must name this panel on a loopback name, which a rebinding attacker's domain never does. */
    public static boolean hostOk(String host, int port) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("127.0.0.1:" + port) || h.equals("localhost:" + port);
    }
}

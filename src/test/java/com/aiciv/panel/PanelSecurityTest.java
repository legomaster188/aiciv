package com.aiciv.panel;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PanelSecurityTest {
    @Test
    void tokensAre128BitHexAndDifferEveryTime() {
        String a = PanelSecurity.newToken();
        assertTrue(a.matches("[0-9a-f]{32}"), a);
        assertNotEquals(a, PanelSecurity.newToken());
    }

    @Test
    void onlyTheExactTokenPasses() {
        String t = PanelSecurity.newToken();
        assertTrue(PanelSecurity.tokenOk(t, t));
        assertFalse(PanelSecurity.tokenOk(t, null));
        assertFalse(PanelSecurity.tokenOk(t, ""));
        assertFalse(PanelSecurity.tokenOk(t, t.substring(1)));
        assertFalse(PanelSecurity.tokenOk(t, t + "0"));
        assertFalse(PanelSecurity.tokenOk(t, t.toUpperCase()));
        assertFalse(PanelSecurity.tokenOk(null, "x"));
    }

    @Test
    void originMustBeAbsentOrThePanelItself() {
        assertTrue(PanelSecurity.originOk(null, 8765));
        assertTrue(PanelSecurity.originOk("http://127.0.0.1:8765", 8765));
        assertTrue(PanelSecurity.originOk("http://localhost:8765", 8765));
        assertTrue(PanelSecurity.originOk("HTTP://LOCALHOST:8765", 8765));
        assertFalse(PanelSecurity.originOk("http://localhost:8766", 8765));
        assertFalse(PanelSecurity.originOk("http://localhost", 8765));
        assertFalse(PanelSecurity.originOk("https://localhost:8765", 8765));
        assertFalse(PanelSecurity.originOk("http://evil.example", 8765));
        assertFalse(PanelSecurity.originOk("http://localhost:8765.evil.example", 8765));
        assertFalse(PanelSecurity.originOk("null", 8765));
        assertFalse(PanelSecurity.originOk("", 8765));
    }

    @Test
    void hostMustNameALoopbackPanelAddress() {
        assertTrue(PanelSecurity.hostOk("127.0.0.1:8765", 8765));
        assertTrue(PanelSecurity.hostOk("localhost:8765", 8765));
        assertTrue(PanelSecurity.hostOk("LocalHost:8765", 8765));
        assertFalse(PanelSecurity.hostOk(null, 8765));
        assertFalse(PanelSecurity.hostOk("localhost", 8765));
        assertFalse(PanelSecurity.hostOk("localhost:9", 8765));
        assertFalse(PanelSecurity.hostOk("rebind.evil.example:8765", 8765));
        assertFalse(PanelSecurity.hostOk("127.0.0.1.evil.example:8765", 8765));
    }
}

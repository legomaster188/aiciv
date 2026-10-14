package com.aiciv.stronghold;

import static org.junit.jupiter.api.Assertions.*;

import com.aiciv.nav.Pos;
import java.util.List;
import org.junit.jupiter.api.Test;

class FrontierTest {
    @Test
    void visitedBucketsAreSkipped() {
        Frontier f = new Frontier(4);
        f.visit(new Pos(1, 64, 1));
        assertTrue(f.visited(new Pos(3, 65, 2)));
        assertFalse(f.visited(new Pos(4, 64, 1)));
        assertFalse(f.visited(new Pos(-1, 64, 1)));
        Pos next = f.next(new Pos(0, 64, 0), List.of(new Pos(2, 64, 2), new Pos(6, 64, 0), new Pos(10, 64, 0))).orElseThrow();
        assertEquals(new Pos(6, 64, 0), next);
    }

    @Test
    void sameHeightCandidatesAreCheaperThanNearerOnesAtAnotherHeight() {
        Frontier f = new Frontier(4);
        Pos near = new Pos(8, 70, 0), far = new Pos(16, 65, 0);
        assertEquals(far, f.next(new Pos(0, 64, 0), List.of(near, far)).orElseThrow());
        assertEquals(near, f.next(new Pos(0, 64, 0), List.of(near)).orElseThrow());
    }

    @Test
    void nothingLeft() {
        Frontier f = new Frontier(4);
        f.visit(new Pos(0, 0, 0));
        assertTrue(f.next(new Pos(0, 0, 0), List.of(new Pos(1, 1, 1))).isEmpty());
        assertTrue(f.next(new Pos(0, 0, 0), List.of()).isEmpty());
    }
}

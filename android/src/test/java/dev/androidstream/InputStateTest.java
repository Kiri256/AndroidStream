package dev.androidstream;

import org.junit.Test;
import static org.junit.Assert.*;

public class InputStateTest {
    @Test public void crossChannelReorderingDoesNotLeavePressedPointer() {
        InputState s = new InputState();
        assertFalse(s.accept("move", 1, 2, 0, 0));
        assertTrue(s.accept("down", 1, 1, 0, 0));
        assertTrue(s.accept("move", 1, 3, 0, 0));
        assertFalse(s.accept("move", 1, 2, 0, 0));
        assertTrue(s.accept("up", 1, 4, 0, 0));
        assertFalse(s.accept("move", 1, 5, 0, 0));
        assertTrue(s.accept("down", 2, 6, 0, 0));
    }
    @Test public void oldGeometryAndOtherGesturesCannotInject() {
        InputState s = new InputState();
        assertFalse(s.accept("down", 1, 1, 0, 1));
        assertTrue(s.accept("down", 1, 1, 1, 1));
        assertFalse(s.accept("up", 2, 2, 1, 1));
        s.reset();
        assertTrue(s.accept("down", 2, 3, 1, 1));
    }
    @Test public void coordinatesRespectDisplayExtent() {
        assertEquals(1079f, InputState.coordinate(1, 1080), 0);
        assertEquals(0f, InputState.coordinate(0, 1080), 0);
        assertThrows(IllegalArgumentException.class, () -> InputState.coordinate(Double.NaN, 1080));
        assertThrows(IllegalArgumentException.class, () -> InputState.coordinate(1.1, 1080));
    }
}

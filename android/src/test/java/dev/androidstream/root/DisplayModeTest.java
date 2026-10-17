package dev.androidstream.root;

import org.junit.Test;
import static org.junit.Assert.*;

public class DisplayModeTest {
    private static int[] mode(int width, int height, int rotation, float fps, int modeId) {
        return new int[]{width, height, rotation, 0, Float.floatToIntBits(fps), modeId};
    }

    @Test public void sizeRotationAndRateCommitTogetherAfterStableWindow() {
        DisplayMode mode = new DisplayMode(mode(2032, 3048, 0, 120, 1));
        assertArrayEquals(mode(2032, 3048, 0, 120, 1), mode.update(mode(3048, 2032, 1, 60, 2), 100));
        assertArrayEquals(mode(2032, 3048, 0, 120, 1), mode.update(mode(3048, 2032, 1, 60, 2), 2099));
        assertArrayEquals(mode(3048, 2032, 1, 60, 2), mode.update(mode(3048, 2032, 1, 60, 2), 2100));
    }

    @Test public void changingCandidateRestartsDebounceWindow() {
        DisplayMode mode = new DisplayMode(mode(2032, 3048, 0, 120, 1));
        mode.update(mode(2032, 3048, 0, 60, 2), 0);
        assertArrayEquals(mode(2032, 3048, 0, 120, 1), mode.update(mode(2032, 3048, 0, 90, 3), 1900));
        assertArrayEquals(mode(2032, 3048, 0, 120, 1), mode.update(mode(2032, 3048, 0, 90, 3), 3899));
        assertArrayEquals(mode(2032, 3048, 0, 90, 3), mode.update(mode(2032, 3048, 0, 90, 3), 3900));
    }

    @Test public void smallRateJitterDoesNotCreateModeChanges() {
        DisplayMode mode = new DisplayMode(mode(2032, 3048, 0, 59.94f, 1));
        assertArrayEquals(mode(2032, 3048, 0, 59.94f, 1), mode.update(mode(2032, 3048, 0, 60, 1), 500));
    }
}

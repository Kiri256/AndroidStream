package dev.androidstream.root;

import org.junit.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.Assert.*;

public final class ScreenPowerTest {
    @Test public void heartbeatLossRestoresPanel() throws Exception {
        List<Boolean> calls = new ArrayList<>();
        ScreenPower screen = new ScreenPower(calls::add);
        screen.setOff(true, 100);
        screen.heartbeat(4000);
        assertFalse(screen.check(5100));
        assertTrue(screen.check(9000));
        assertFalse(screen.isOff());
        assertEquals(List.of(true, false), calls);
    }
    @Test public void explicitRestoreIsIdempotent() throws Exception {
        List<Boolean> calls = new ArrayList<>();
        ScreenPower screen = new ScreenPower(calls::add);
        screen.restore(); screen.setOff(true, 0); screen.restore(); screen.restore();
        assertEquals(List.of(true, false), calls);
    }
    @Test public void partialOffFailureRollsBack() {
        List<Boolean> calls = new ArrayList<>();
        ScreenPower screen = new ScreenPower(off -> { calls.add(off); if (off) throw new IOException(); });
        assertThrows(IOException.class, () -> screen.setOff(true, 0));
        assertFalse(screen.isOff());
        assertEquals(List.of(true, false), calls);
    }
    @Test public void failedRestoreRemainsArmedAndRetriesWithBoundedFrequency() throws Exception {
        boolean[] fail = {true};
        ScreenPower screen = new ScreenPower(off -> { if (!off && fail[0]) throw new IOException(); });
        screen.setOff(true, 0);
        assertThrows(IOException.class, () -> screen.check(5000));
        assertTrue(screen.isOff());
        assertFalse(screen.check(5001));
        fail[0] = false;
        assertTrue(screen.check(10000));
    }
    @Test public void twoHourLimitRestoresEvenWithLiveHeartbeats() throws Exception {
        ScreenPower screen = new ScreenPower(off -> {});
        screen.setOff(true, 0); screen.heartbeat(7200000);
        assertTrue(screen.check(7200000));
    }
    @Test public void newLeaseDoesNotInheritPreviousRetryDeadline() throws Exception {
        ScreenPower screen = new ScreenPower(off -> {});
        screen.setOff(true, 0); assertTrue(screen.check(5000));
        screen.setOff(true, 5001);
        assertFalse(screen.check(10000));
        assertTrue(screen.check(10001));
    }
}

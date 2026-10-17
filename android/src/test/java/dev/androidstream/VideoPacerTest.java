package dev.androidstream;

import org.junit.Test;
import static org.junit.Assert.*;

public class VideoPacerTest {
    @Test public void idleWaitWakesOnPacketAndCanBeCancelled() throws Exception {
        VideoPacer pacer = new VideoPacer();
        java.util.concurrent.FutureTask<VideoPacer.Frame> task = new java.util.concurrent.FutureTask<>(pacer::take);
        Thread thread = new Thread(task); thread.start();
        try {
            assertFalse(task.isDone());
            pacer.offer(new byte[]{7}, 0, true, 1);
            assertEquals(7, task.get(2, java.util.concurrent.TimeUnit.SECONDS).packet()[0]);
            java.util.concurrent.FutureTask<VideoPacer.Frame> waiting = new java.util.concurrent.FutureTask<>(pacer::take);
            Thread idle = new Thread(waiting); idle.start(); idle.interrupt(); idle.join(2000);
            assertFalse(idle.isAlive());
        } finally { thread.interrupt(); thread.join(2000); }
    }
    @Test public void ipcBurstPreservesPredictionOrder() {
        VideoPacer pacer = new VideoPacer();
        for (int i = 0; i < 5; i++) assertFalse(pacer.offer(new byte[]{(byte)i}, i * 16666L, i == 0, 1_000_000));
        for (int i = 0; i < 5; i++) assertEquals(i, pacer.poll().packet()[0]);
        assertNull(pacer.poll());
    }
    @Test public void staleBacklogWaitsForNewKeyInsteadOfSendingBrokenChain() {
        VideoPacer pacer = new VideoPacer();
        pacer.offer(new byte[1], 0, true, 1);
        assertTrue(pacer.offer(new byte[1], 16666, false, 101_000_002L));
        assertNull(pacer.poll());
        pacer.offer(new byte[]{2}, 33333, false, 102_000_000);
        assertNull(pacer.poll());
        pacer.offer(new byte[]{3}, 50000, true, 103_000_000);
        assertEquals(3, pacer.poll().packet()[0]);
    }
    @Test public void burstCannotCreateUnboundedLatency() {
        VideoPacer pacer = new VideoPacer();
        for (int i = 0; i < 6; i++) pacer.offer(new byte[1], i, i == 0, i);
        assertTrue(pacer.offer(new byte[1], 7, false, 7));
        assertNull(pacer.poll());
        pacer.clear();
        pacer.offer(new byte[1], 8, false, 8);
        assertNull(pacer.poll());
    }
}

package dev.androidstream;

import org.junit.Test;
import static org.junit.Assert.*;

public class EncodedFrameQueueTest {
    @Test public void retainsPacketSliceWithoutDuplicatingPayload() {
        EncodedFrameQueue queue = new EncodedFrameQueue();
        byte[] packet = new byte[1024];
        queue.add(1000, packet, 12, true);
        EncodedFrameQueue.Frame frame = queue.take(1000).frame();
        assertSame(packet, frame.bytes());
        assertEquals(12, frame.offset());
    }
    @Test public void sixtyFpsPredictionChainSurvivesTimestampClamping() {
        EncodedFrameQueue q = new EncodedFrameQueue();
        for (int i = 0; i < 180; i++) {
            long ns = 1_000_000_000L + i * 16_666_000L;
            byte[] data = new byte[]{(byte)i};
            assertNotNull(q.add(ns, data, i % 60 == 0).frame());
            EncodedFrameQueue.Delivery delivery = q.take(ns - 850_000);
            assertNotNull(delivery.frame());
            assertSame(data, delivery.frame().bytes());
            assertFalse(delivery.requestKey());
        }
    }
    @Test public void skippedReferenceRequiresKeyframe() {
        EncodedFrameQueue q = new EncodedFrameQueue();
        q.add(10_000_000, new byte[1], true); q.take(10_000_000);
        q.add(30_000_000, new byte[1], false);
        q.add(50_000_000, new byte[1], false);
        assertTrue(q.take(50_000_000).requestKey());
        assertNull(q.add(70_000_000, new byte[1], false).frame());
        assertNotNull(q.add(90_000_000, new byte[1], true).frame());
        assertNotNull(q.take(90_000_000).frame());
    }
    @Test public void boundedQueueAndStaleCallbacks() {
        EncodedFrameQueue q = new EncodedFrameQueue();
        q.add(100_000_000, new byte[1], true);
        assertNull(q.take(1_000_000).frame());
        for (int i = 1; i < 8; i++) q.add(100_000_000 + i * 20_000_000, new byte[1], false);
        assertTrue(q.add(300_000_000, new byte[1], false).requestKey());
        assertNull(q.take(300_000_000).frame());
        assertNotNull(q.add(320_000_000, new byte[1], true).frame());
    }
}

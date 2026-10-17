package dev.androidstream;

import org.junit.Test;
import org.webrtc.EncodedImage;
import java.nio.ByteBuffer;
import static org.junit.Assert.*;

public class EncodedBufferPoolTest {
    @Test public void nativeOwnerMustReleaseBeforeReuse() {
        EncodedBufferPool pool = new EncodedBufferPool();
        pool.recycle(ByteBuffer.allocateDirect(256));
        ByteBuffer buffer = pool.acquire(128);
        EncodedImage image = EncodedImage.builder().setBuffer(buffer.slice(), () -> pool.recycle(buffer))
                .setFrameType(EncodedImage.FrameType.VideoFrameDelta).createEncodedImage();
        assertEquals(128, image.buffer.capacity());
        image.retain(); image.release();
        assertEquals(0, pool.cachedCount());
        assertNotSame(buffer, pool.acquire(128));
        image.release();
        assertSame(buffer, pool.acquire(128));
        assertEquals(128, buffer.limit());
    }
    @Test public void cacheCannotGrowWithFrameSizesOrRetainHugeIdrs() {
        EncodedBufferPool pool = new EncodedBufferPool();
        for (int i = 1; i <= 10; i++) pool.recycle(ByteBuffer.allocateDirect(i * 256));
        assertEquals(4, pool.cachedCount());
        pool.acquire(256);
        pool.recycle(ByteBuffer.allocateDirect(2 * 1024 * 1024 + 1));
        assertEquals(3, pool.cachedCount());
    }
}

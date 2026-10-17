package dev.androidstream;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/** Reuse only buffers whose final WebRTC reference has been released. */
final class EncodedBufferPool {
    private final ArrayDeque<ByteBuffer> buffers = new ArrayDeque<>();
    synchronized ByteBuffer acquire(int size) {
        ByteBuffer selected = null;
        for (ByteBuffer buffer : buffers)
            if (buffer.capacity() >= size && (selected == null || buffer.capacity() < selected.capacity())) selected = buffer;
        if (selected == null) selected = ByteBuffer.allocateDirect(size);
        else {
            for (var iterator = buffers.iterator(); iterator.hasNext();) {
                if (iterator.next() == selected) { iterator.remove(); break; }
            }
        }
        selected.clear(); selected.limit(size);
        return selected;
    }
    synchronized void recycle(ByteBuffer buffer) {
        if (buffer.capacity() <= 2 * 1024 * 1024) {
            if (buffers.size() == 4) buffers.removeFirst();
            buffer.clear(); buffers.add(buffer);
        }
    }
    synchronized int cachedCount() { return buffers.size(); }
}

package dev.androidstream;

import java.util.NavigableMap;
import java.util.TreeMap;

/** Bounded encoded-frame handoff, tolerating WebRTC's capture-time clamp. */
final class EncodedFrameQueue {
    record Frame(byte[] bytes, int offset, boolean key) {}
    record Delivery(Frame frame, boolean requestKey) {}
    private final NavigableMap<Long, Frame> frames = new TreeMap<>();
    private boolean needsKey = true;
    synchronized Delivery add(long timestamp, byte[] bytes, boolean key) {
        return add(timestamp, bytes, 0, key);
    }
    synchronized Delivery add(long timestamp, byte[] bytes, int offset, boolean key) {
        if (offset < 0 || offset >= bytes.length) throw new IllegalArgumentException("Invalid frame offset");
        boolean request = false;
        if (frames.size() >= 8) {
            frames.clear(); needsKey = true; request = true;
        }
        if (needsKey && !key) return new Delivery(null, request);
        if (key) needsKey = false;
        Frame frame = new Frame(bytes, offset, key);
        frames.put(timestamp, frame);
        return new Delivery(frame, request);
    }
    synchronized Delivery take(long timestamp) {
        if (frames.isEmpty()) return new Delivery(null, false);
        Long floor = frames.floorKey(timestamp), ceiling = frames.ceilingKey(timestamp);
        Long match = floor == null ? ceiling : ceiling == null ? floor
                : timestamp - floor <= ceiling - timestamp ? floor : ceiling;
        // Seed/stale callbacks must not steal a real encoded frame.
        if (Math.abs(match - timestamp) > 5_000_000L) return new Delivery(null, false);
        boolean skipped = !frames.headMap(match, false).isEmpty();
        if (skipped) { frames.headMap(match, false).clear(); needsKey = true; }
        Frame frame = frames.remove(match);
        if (frame.key) needsKey = false;
        return new Delivery(needsKey ? null : frame, skipped);
    }
    synchronized void clear() { frames.clear(); needsKey = true; }
}

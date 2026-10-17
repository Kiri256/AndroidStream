package dev.androidstream;

import java.util.ArrayDeque;

/** Smooth encoder bursts without allowing a queue of stale prediction frames. */
final class VideoPacer {
    record Frame(byte[] packet, long ptsUs, long queuedNs) {}
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private boolean needsKey = true;
    synchronized boolean offer(byte[] packet, long ptsUs, boolean key, long now) {
        boolean reset = frames.size() >= 6 || !frames.isEmpty() && now - frames.peekFirst().queuedNs > 100_000_000L;
        if (reset) { frames.clear(); needsKey = true; }
        if (!needsKey || key) {
            frames.addLast(new Frame(packet, ptsUs, now));
            needsKey = false;
            notifyAll();
        }
        return reset;
    }
    synchronized Frame poll() { return frames.pollFirst(); }
    synchronized Frame take() throws InterruptedException {
        while (frames.isEmpty()) wait();
        return frames.removeFirst();
    }
    synchronized void clear() { frames.clear(); needsKey = true; }
}

package dev.androidstream.root;

import dev.androidstream.VideoPolicy;

/** Commits a display mode only after the complete mode stays stable for two seconds. */
final class DisplayMode {
    private int[] current, pending;
    private long since;

    DisplayMode(int[] initial) { current = initial.clone(); }

    int[] update(int[] sample, long nowMs) {
        VideoPolicy.refreshRate(Float.intBitsToFloat(sample[4]));
        if (sameMode(current, sample)) {
            pending = null;
            since = nowMs;
        } else if (pending == null || !sameIdentity(pending, sample)) {
            pending = sample.clone();
            since = nowMs;
        } else {
            pending = sample.clone();
            if (nowMs - since >= 2000) {
                current = pending;
                pending = null;
            }
        }
        return current.clone();
    }

    private static boolean sameMode(int[] first, int[] second) {
        return sameIdentity(first, second)
                && Math.abs(Float.intBitsToFloat(first[4]) - Float.intBitsToFloat(second[4])) < 0.1f;
    }

    private static boolean sameIdentity(int[] first, int[] second) {
        return first[0] == second[0] && first[1] == second[1] && first[2] == second[2]
                && first[3] == second[3] && first[5] == second[5];
    }
}

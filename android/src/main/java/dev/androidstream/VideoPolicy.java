package dev.androidstream;

import android.media.MediaCodecInfo;

/** Single built-in video policy; no remotely configurable encoder modes. */
public final class VideoPolicy {
    private static final long[][] H264_LEVELS = {{31, 3600, 108000, 14000000}, {32, 5120, 216000, 20000000},
            {40, 8192, 245760, 20000000}, {41, 8192, 245760, 50000000},
            {42, 8704, 522240, 50000000}, {50, 22080, 589824, 135000000},
            {51, 36864, 983040, 240000000}, {52, 36864, 2073600, 240000000}};
    private VideoPolicy() {}
    public static boolean h264BaselineProfile(int profile) {
        return h264BaselineProfileId(profile) != 0;
    }
    public static int h264BaselineProfileId(int profile) {
        int baseline = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline;
        int constrained = MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline;
        if (profile == 0 || (profile & ~(baseline | constrained)) != 0) return 0;
        if ((profile & constrained) != 0) return constrained;
        return (profile & baseline) != 0 ? baseline : 0;
    }
    public static float refreshRate(double value) {
        if (!Double.isFinite(value) || value < 1 || value > Float.MAX_VALUE)
            throw new IllegalArgumentException("Invalid display refresh rate");
        return (float) value;
    }
    public static int alignedDimension(int value, int alignment) {
        if (value < 1 || alignment < 1 || value < alignment)
            throw new IllegalArgumentException("Invalid native display dimensions");
        return value / alignment * alignment;
    }
    public static int[] largestSupportedSize(int width, int height, int widthAlignment, int heightAlignment,
                                             int minWidth, int minHeight, java.util.function.BiPredicate<Integer, Integer> supported) {
        if (width < 1 || height < 1 || widthAlignment < 1 || heightAlignment < 1 || minWidth < 1 || minHeight < 1)
            throw new IllegalArgumentException("Invalid video size range");
        int minW = ((minWidth + widthAlignment - 1) / widthAlignment) * widthAlignment;
        int minH = ((minHeight + heightAlignment - 1) / heightAlignment) * heightAlignment;
        int[] best = null;
        double low = 0, high = 1;
        for (int i = 0; i < 28; i++) {
            double scale = (low + high) / 2;
            int w = (int) (width * scale) / widthAlignment * widthAlignment;
            int h = (int) (height * scale) / heightAlignment * heightAlignment;
            if (w >= minW && h >= minH && supported.test(w, h)) {
                best = new int[]{w, h};
                low = scale;
            } else high = scale;
        }
        int nativeWidth = alignedDimension(width, widthAlignment), nativeHeight = alignedDimension(height, heightAlignment);
        if (supported.test(nativeWidth, nativeHeight)) return new int[]{nativeWidth, nativeHeight};
        return best;
    }
    static long frameIntervalNs(float fps) {
        return Math.max(1, Math.round(1_000_000_000.0 / refreshRate(fps)));
    }
    /** H.264 Annex A Baseline limits: level_idc, MaxFS, MaxMBPS, MaxBR (bit/s). */
    public static int h264Level(int width, int height, float fps, int bitrate) {
        return h264Level(width, height, fps, bitrate, 52);
    }
    public static int h264Level(int width, int height, float fps, int bitrate, int maxLevelIdc) {
        if (width < 1 || height < 1 || bitrate < 1) throw new IllegalArgumentException("Invalid video specification");
        refreshRate(fps);
        long columns = (width + 15L) / 16, rows = (height + 15L) / 16;
        long blocks = columns * rows;
        for (long[] limit : H264_LEVELS) {
            if (limit[0] <= maxLevelIdc && blocks <= limit[1] && blocks * (double) fps <= limit[2] && bitrate <= limit[3]
                    && columns * columns <= 8 * limit[1] && rows * rows <= 8 * limit[1]) return (int) limit[0];
        }
        throw new IllegalArgumentException("Native display exceeds H264 level limits");
    }
    public static float supportedFrameRate(int width, int height, float requested, float codecMaximum, int bitrate, int maxLevelIdc) {
        refreshRate(requested);
        refreshRate(codecMaximum);
        long columns = (width + 15L) / 16, rows = (height + 15L) / 16;
        long blocks = columns * rows;
        double limit = 0;
        for (long[] level : H264_LEVELS) {
            if (level[0] <= maxLevelIdc && blocks <= level[1] && bitrate <= level[3]
                    && columns * columns <= 8 * level[1] && rows * rows <= 8 * level[1])
                limit = Math.max(limit, level[2] / (double) blocks);
        }
        double upper = Math.min(Math.min(requested, codecMaximum), limit);
        float result = (float) upper;
        if (upper == limit && result >= upper) result = Math.nextDown(result);
        if (result < 1) throw new IllegalArgumentException("No supported H264 frame rate");
        return result;
    }
    public static int levelIdc(int codecLevel) {
        int[] flags = {1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536};
        int[] idcs = {10, 11, 11, 12, 13, 20, 21, 22, 30, 31, 32, 40, 41, 42, 50, 51, 52};
        int remaining = codecLevel, highest = 0;
        for (int i = 0; i < flags.length; i++) {
            if ((remaining & flags[i]) != 0) {
                remaining &= ~flags[i];
                highest = Math.max(highest, idcs[i]);
            }
        }
        return remaining == 0 ? highest : 0;
    }
    /** Validate our generated answer, not untrusted offer text or unrelated media sections. */
    static boolean answerSupportsLevel(String sdp, int required) {
        boolean video = false;
        java.util.regex.Pattern profile = java.util.regex.Pattern.compile("(?:^|[ ;])profile-level-id=42(?:e0|c0)([0-9a-f]{2})(?:;|$)",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        for (String line : sdp.split("\\r?\\n")) {
            if (line.startsWith("m=")) video = line.startsWith("m=video ") && !line.startsWith("m=video 0 ");
            if (video && line.startsWith("a=fmtp:")) {
                java.util.regex.Matcher match = profile.matcher(line);
                if (match.find() && Integer.parseInt(match.group(1), 16) >= required) return true;
            }
        }
        return false;
    }
    static int startBitrate(int lower, int upper) {
        // Initial LAN estimate, not a minimum or a cap; congestion control remains active.
        return codecBitrate(8000000, lower, upper);
    }
    static int initialBitrate(int startKbps) {
        return (int) Math.max(100000L, Math.min(Integer.MAX_VALUE, startKbps * 1000L));
    }
    static int allocatedBitrate(int allocatedBps) {
        return Math.max(100000, allocatedBps);
    }
    static int maxBitrate(int lower, int upper) {
        if (lower <= 0 || upper < lower) throw new IllegalArgumentException("Unsupported codec bitrate range");
        return upper;
    }
    public static int codecBitrate(int requested, int lower, int upper) {
        return Math.max(lower, Math.min(maxBitrate(lower, upper), requested));
    }
}

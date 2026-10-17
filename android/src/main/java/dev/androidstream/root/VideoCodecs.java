package dev.androidstream.root;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import dev.androidstream.VideoPolicy;
import java.util.LinkedHashSet;
import java.util.Locale;

/** Keeps hardware capability queries separate from capture and signaling. */
public final class VideoCodecs {
    public static final String H264 = "H264", H265 = "H265", AV1 = "AV1";
    public static int av1LevelIndex(int level) {
        int[] flags = {1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024, 2048,
                4096, 8192, 16384, 32768, 65536, 131072, 262144, 524288,
                1048576, 2097152, 4194304, 8388608};
        for (int i = 0; i < flags.length; i++) if (level == flags[i]) return i;
        return -1;
    }
    public record Selection(MediaCodecInfo codec, String codecName, String mime, int width, int height, float fps,
                            int profile, int codecLevel, int levelIdc, boolean nativeSize) {
        public String profileLevelId() {
            return String.format(java.util.Locale.ROOT, "42e0%02x", levelIdc);
        }
        public int codecProfile() { return profile; }
        public int h264CodecLevel() {
            return switch (levelIdc) {
                case 31 -> MediaCodecInfo.CodecProfileLevel.AVCLevel31;
                case 32 -> MediaCodecInfo.CodecProfileLevel.AVCLevel32;
                case 40 -> MediaCodecInfo.CodecProfileLevel.AVCLevel4;
                case 41 -> MediaCodecInfo.CodecProfileLevel.AVCLevel41;
                case 42 -> MediaCodecInfo.CodecProfileLevel.AVCLevel42;
                case 50 -> MediaCodecInfo.CodecProfileLevel.AVCLevel5;
                case 51 -> MediaCodecInfo.CodecProfileLevel.AVCLevel51;
                case 52 -> MediaCodecInfo.CodecProfileLevel.AVCLevel52;
                default -> throw new IllegalArgumentException("Unsupported H264 level");
            };
        }
    }
    public static final class UnsupportedVideoException extends IllegalStateException {
        UnsupportedVideoException(String message) { super(message); }
    }
    public static Selection nativeDisplay(int width, int height, float fps) { return choose(width, height, fps, null, H264); }
    public static Selection nativeDisplay(int width, int height, float fps, String codecName) { return choose(width, height, fps, null, codecName); }
    public static Selection select(int width, int height, float fps, String codec) { return choose(width, height, fps, codec, H264); }
    public static Selection select(int width, int height, float fps, String codec, String codecName) { return choose(width, height, fps, codec, codecName); }
    public static Selection selectCandidate(int width, int height, float fps, String codecName, int ordinal) {
        return chooseOrdinal(width, height, fps, null, codecName, ordinal);
    }
    public static java.util.List<String> supportedNativeCodecs(int width, int height, float fps) {
        java.util.ArrayList<String> supported = new java.util.ArrayList<>(3);
        for (String name : new String[]{H264, H265, AV1}) {
            try { nativeDisplay(width, height, fps, name); supported.add(name); }
            catch (UnsupportedVideoException ignored) {}
        }
        return java.util.List.copyOf(supported);
    }
    private static Selection choose(int width, int height, float fps, String codec, String codecName) {
        return chooseOrdinal(width, height, fps, codec, codecName, 0);
    }
    private static Selection chooseOrdinal(int width, int height, float fps, String codec, String codecName, int ordinal) {
        VideoPolicy.refreshRate(fps);
        if (!H264.equals(codecName) && !H265.equals(codecName) && !AV1.equals(codecName)) throw new IllegalArgumentException("Unsupported video codec");
        String type = H264.equals(codecName) ? "video/avc" : H265.equals(codecName) ? "video/hevc" : "video/av01";
        Selection best = null;
        int candidateIndex = 0;
        LinkedHashSet<String> rejected = new LinkedHashSet<>();
        boolean sawEncoder = false;
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) continue;
            if (codec != null && !info.getName().equals(codec)) continue;
            for (String supported : info.getSupportedTypes()) {
                if (!supported.equalsIgnoreCase(type)) continue;
                sawEncoder = true;
                if (info.isSoftwareOnly()) {
                    rejected.add("系统只报告了软件 " + codecName + " 编码器");
                    continue;
                }
                MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
                boolean surface = false;
                for (int color : caps.colorFormats) if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) surface = true;
                if (!surface) {
                    rejected.add("厂商 " + codecName + " 编码器未声明 Surface 输入");
                    continue;
                }
                int declaredCodecLevel = 0;
                int declaredProfile = H264.equals(codecName) ? MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                        : H265.equals(codecName) ? MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
                        : MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8;
                for (MediaCodecInfo.CodecProfileLevel level : caps.profileLevels) {
                    if ((level.profile & MediaCodecInfo.CodecProfileLevel.HEVCProfileMain) != 0
                            && VideoPolicy.hevcMainLevelIdc(level.level) != 0) {
                        declaredProfile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain;
                        if (VideoPolicy.hevcMainLevelIdc(level.level) > VideoPolicy.hevcMainLevelIdc(declaredCodecLevel)) declaredCodecLevel = level.level;
                    }
                    else if (AV1.equals(codecName) && level.profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8) {
                        declaredProfile = MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8;
                        if (av1LevelIndex(level.level) > av1LevelIndex(declaredCodecLevel)) declaredCodecLevel = level.level;
                    }
                }
                try {
                    MediaCodecInfo.VideoCapabilities video = caps.getVideoCapabilities();
                    android.util.Range<Integer> bitrate = video.getBitrateRange();
                    int maxBitrate = bitrate.getUpper();
                    int requiredLevel = 0;
                    int profile = declaredProfile;
                    int selectedCodecLevel = 0;
                    if (H264.equals(codecName)) {
                        try { requiredLevel = VideoPolicy.h264Level(width, height, fps, maxBitrate, 52); }
                        catch (IllegalArgumentException unsupported) { requiredLevel = 52; }
                        profile = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline;
                    } else {
                        selectedCodecLevel = declaredCodecLevel;
                        requiredLevel = H265.equals(codecName) ? VideoPolicy.hevcMainLevelIdc(declaredCodecLevel)
                                : Math.max(0, av1LevelIndex(declaredCodecLevel));
                    }
                    Selection candidate = new Selection(info, codecName, type, width, height, fps, profile,
                            selectedCodecLevel, requiredLevel, true);
                    if (ordinal > 0) {
                        if (candidateIndex++ != ordinal) continue;
                        return candidate;
                    }
                    long area = (long) width * height, bestArea = best == null ? -1 : (long) best.width() * best.height();
                    if (best == null || area > bestArea || area == bestArea && requiredLevel > best.levelIdc()) best = candidate;
                } catch (IllegalArgumentException unsupported) {
                    rejected.add(unsupported.getMessage() != null && unsupported.getMessage().contains("bitrate")
                            ? "读取编码器硬件码率范围失败" : "读取编码器硬件能力失败");
                }
            }
        }
        if (best != null) return best;
        String requested = String.format(Locale.ROOT, "%.2f", fps);
        String reason = rejected.isEmpty() ? (sawEncoder ? "没有可用的非软件 " + codecName + " Surface 编码器" : "系统未报告 " + codecName + " 编码器")
                : String.join("；", rejected);
        throw new UnsupportedVideoException("手机 " + codecName + " Surface 硬编不支持原生规格 " + width + "×" + height
                + " @ " + requested + " Hz（" + codecName + " Surface 硬编，码率受编码器能力限制）：" + reason);
    }
}

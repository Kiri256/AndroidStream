package dev.androidstream.root;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import dev.androidstream.VideoPolicy;
import java.util.LinkedHashSet;
import java.util.Locale;

/** Keeps hardware capability queries separate from capture and signaling. */
public final class VideoCodecs {
    public record Selection(MediaCodecInfo codec, int width, int height, float fps, int profile, int levelIdc, boolean nativeSize) {
        public String profileLevelId() {
            return String.format(java.util.Locale.ROOT, "42e0%02x", levelIdc);
        }
        public int codecProfile() { return profile; }
        public int codecLevel() {
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
    public static Selection nativeDisplay(int width, int height, float fps) { return choose(width, height, fps, null); }
    public static Selection select(int width, int height, float fps, String codec) { return choose(width, height, fps, codec); }
    private static Selection choose(int width, int height, float fps, String codec) {
        VideoPolicy.refreshRate(fps);
        String type = "video/avc";
        Selection best = null;
        LinkedHashSet<String> rejected = new LinkedHashSet<>();
        boolean sawAvcEncoder = false;
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) continue;
            if (codec != null && !info.getName().equals(codec)) continue;
            for (String supported : info.getSupportedTypes()) {
                if (!supported.equalsIgnoreCase(type)) continue;
                sawAvcEncoder = true;
                if (info.isSoftwareOnly()) {
                    rejected.add("系统只报告了软件 H.264 编码器");
                    continue;
                }
                MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
                boolean surface = false;
                for (int color : caps.colorFormats) if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) surface = true;
                if (!surface) {
                    rejected.add("厂商 H.264 编码器未声明 Surface 输入");
                    continue;
                }
                int declaredLevel = 0;
                for (MediaCodecInfo.CodecProfileLevel level : caps.profileLevels) {
                    declaredLevel = Math.max(declaredLevel, VideoPolicy.levelIdc(level.level));
                }
                try {
                    MediaCodecInfo.VideoCapabilities video = caps.getVideoCapabilities();
                    android.util.Range<Integer> bitrate = video.getBitrateRange();
                    int maxBitrate = bitrate.getUpper();
                    if (!video.isSizeSupported(width, height)) {
                        rejected.add("编码器不支持原生尺寸 " + width + "×" + height);
                        continue;
                    }
                    if (!video.areSizeAndRateSupported(width, height, fps)) {
                        rejected.add(String.format(Locale.ROOT, "编码器不支持原生帧率 %.2f Hz", fps));
                        continue;
                    }
                    int requiredLevel;
                    try { requiredLevel = VideoPolicy.h264Level(width, height, fps, maxBitrate, declaredLevel == 0 ? 52 : declaredLevel); }
                    catch (IllegalArgumentException unsupported) {
                        rejected.add("原生尺寸、帧率与编码器最大码率超过编码器声明的 H.264 Level 能力");
                        continue;
                    }
                    int profile = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline;
                    Selection candidate = new Selection(info, width, height, fps, profile, requiredLevel, true);
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
        String reason = rejected.isEmpty() ? (sawAvcEncoder ? "没有可用的非软件 H.264 Surface 编码器" : "系统未报告 H.264 编码器")
                : String.join("；", rejected);
        throw new UnsupportedVideoException("手机 H.264 Surface 硬编不支持原生规格 " + width + "×" + height
                + " @ " + requested + " Hz（码率受编码器与 H.264 Level 能力限制）：" + reason);
    }
}

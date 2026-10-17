package dev.androidstream;

import org.junit.Test;
import static org.junit.Assert.*;

public class VideoPolicyTest {
    @Test public void lanStartEstimateRespectsHardware() {
        assertEquals(8000000, VideoPolicy.startBitrate(100000, 80000000));
        assertEquals(4000000, VideoPolicy.startBitrate(100000, 4000000));
        assertEquals(12000000, VideoPolicy.startBitrate(12000000, 80000000));
        assertEquals(40000000, VideoPolicy.allocatedBitrate(40000000));
        assertThrows(IllegalArgumentException.class, () -> VideoPolicy.startBitrate(100, 10));
    }
    @Test public void startupConversionPreservesHighTargetsWithoutOverflow() {
        assertEquals(100000, VideoPolicy.initialBitrate(-1));
        assertEquals(300000, VideoPolicy.initialBitrate(300));
        assertEquals(20000000, VideoPolicy.initialBitrate(20000));
        assertEquals(Integer.MAX_VALUE, VideoPolicy.initialBitrate(Integer.MAX_VALUE));
    }
    @Test public void bandwidthFeedbackHasNoApplicationCeiling() {
        assertEquals(100000, VideoPolicy.allocatedBitrate(0));
        assertEquals(8000000, VideoPolicy.allocatedBitrate(8000000));
        assertEquals(40000000, VideoPolicy.allocatedBitrate(40000000));
        assertEquals(Integer.MAX_VALUE, VideoPolicy.allocatedBitrate(Integer.MAX_VALUE));
    }
    @Test public void codecUsesHardwareRangeWithoutApplicationCeiling() {
        assertEquals(40000000, VideoPolicy.codecBitrate(40000000, 1000, 80000000));
        assertEquals(80000000, VideoPolicy.codecBitrate(Integer.MAX_VALUE, 1000, 80000000));
        assertEquals(30000000, VideoPolicy.codecBitrate(Integer.MAX_VALUE, 1000, 30000000));
        assertEquals(1000, VideoPolicy.codecBitrate(0, 1000, 80000000));
        assertThrows(IllegalArgumentException.class, () -> VideoPolicy.codecBitrate(1, 0, 100));
        assertThrows(IllegalArgumentException.class, () -> VideoPolicy.codecBitrate(1, 100, 10));
    }
    @Test public void senderMaximumUsesHardwareRange() {
        assertEquals(200000000, VideoPolicy.maxBitrate(100000, 200000000));
        assertEquals(30000000, VideoPolicy.maxBitrate(100000, 30000000));
        assertEquals(120000000, VideoPolicy.codecBitrate(130000000, 110000000, 120000000));
    }
    @Test public void nativeSizeOnlyUsesRequiredCodecAlignment() {
        assertEquals(2670, VideoPolicy.alignedDimension(2670, 2));
        assertEquals(2656, VideoPolicy.alignedDimension(2670, 16));
        assertEquals(3200, VideoPolicy.alignedDimension(3200, 16));
        assertThrows(IllegalArgumentException.class, () -> VideoPolicy.alignedDimension(0, 2));
    }
    @Test public void unsupportedNativeSizeFallsBackToLargestAspectPreservingHardwareSize() {
        int[] nativeSize = VideoPolicy.largestSupportedSize(2032, 3048, 16, 16, 64, 64,
                (w, h) -> w <= 4096 && h <= 4096);
        assertArrayEquals(new int[]{2032, 3040}, nativeSize);
        int[] limited = VideoPolicy.largestSupportedSize(2032, 3048, 16, 16, 64, 64,
                (w, h) -> w <= 1920 && h <= 4096);
        assertArrayEquals(new int[]{1920, 2896}, limited);
        assertNull(VideoPolicy.largestSupportedSize(2032, 3048, 16, 16, 64, 64, (w, h) -> false));
    }
    @Test public void nativeSizeSearchIncludesBaselineLevelFrameRateAndBitrateConstraints() {
        int[] selected = VideoPolicy.largestSupportedSize(2032, 3048, 16, 16, 64, 64, (w, h) -> {
            try {
                VideoPolicy.h264Level(w, h, 60, 100000000, 50);
                return true;
            } catch (IllegalArgumentException unsupported) {
                return false;
            }
        });
        assertNotNull(selected);
        assertTrue(selected[0] < 2032 || selected[1] < 3040);
        assertTrue(VideoPolicy.h264Level(selected[0], selected[1], 60, 100000000, 50) <= 50);
        assertThrows(IllegalArgumentException.class, () -> VideoPolicy.h264Level(2032, 3040, 60, 100000000, 50));
    }
    @Test public void displayClockSupportsHighAndFractionalRefreshRates() {
        assertEquals(59.94f, VideoPolicy.refreshRate(59.94), 0.0001f);
        assertEquals(8333333L, VideoPolicy.frameIntervalNs(120));
        assertEquals(6944444L, VideoPolicy.frameIntervalNs(144));
        assertEquals(16683350L, VideoPolicy.frameIntervalNs(59.94f), 1L);
        assertThrows(IllegalArgumentException.class, () -> VideoPolicy.refreshRate(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> VideoPolicy.refreshRate(0));
    }
    @Test public void h264LevelCoversNativeMacroblockRateAndBitrate() {
        assertEquals(42, VideoPolicy.h264Level(1920, 1080, 60, 50000000));
        assertEquals(50, VideoPolicy.h264Level(1920, 1080, 60, 100000000));
        assertEquals(52, VideoPolicy.h264Level(2032, 3048, 60, 100000000));
        assertEquals(52, VideoPolicy.h264Level(1200, 2670, 120, 100000000));
        assertEquals(52, VideoPolicy.h264Level(2670, 1200, 120, 100000000));
        assertEquals(51, VideoPolicy.h264Level(1920, 1080, 60, 150000000));
        assertThrows(IllegalArgumentException.class, () -> VideoPolicy.h264Level(2032, 3048, 120, 100000000));
        assertThrows(IllegalArgumentException.class, () -> VideoPolicy.h264Level(1440, 3200, 120, 100000000));
    }
    @Test public void nativeDisplayKeepsResolutionAndCapsRateToCodecAndBaselineLimits() {
        float requested = VideoPolicy.refreshRate(120.00001);
        float codecRate = VideoPolicy.supportedFrameRate(2032, 3048, requested, 120, 100000000, 52);
        assertEquals(85.48f, codecRate, 0.02f);
        assertEquals(52, VideoPolicy.h264Level(2032, 3048, codecRate, 100000000, 52));
        assertEquals(60, VideoPolicy.supportedFrameRate(2032, 3048, requested, 60, 100000000, 52), 0.001f);
        assertEquals(85.48f, VideoPolicy.supportedFrameRate(2032, 3048, 120, 120, 100000000, 52), 0.02f);
        assertEquals(40.5f, VideoPolicy.supportedFrameRate(2032, 3048, 120, 120, 100000000, 51), 0.1f);
    }
    @Test public void codecLevelFlagsMapToH264LevelIdentifiers() {
        assertEquals(30, VideoPolicy.levelIdc(256));
        assertEquals(31, VideoPolicy.levelIdc(512));
        assertEquals(52, VideoPolicy.levelIdc(65536));
        assertEquals(41, VideoPolicy.levelIdc(512 | 2048 | 4096));
        assertEquals(0, VideoPolicy.levelIdc(0));
        assertEquals(0, VideoPolicy.levelIdc(512 | 0x40000000));
    }
    @Test public void recognizesBaselineAndConstrainedBaselineProfiles() {
        assertTrue(VideoPolicy.h264BaselineProfile(1));
        assertTrue(VideoPolicy.h264BaselineProfile(65536));
        assertTrue(VideoPolicy.h264BaselineProfile(1 | 65536));
        assertFalse(VideoPolicy.h264BaselineProfile(2));
        assertFalse(VideoPolicy.h264BaselineProfile(2 | 65536));
    }
    @Test public void negotiatedAnswerMustSupportNativeLevelInActiveVideo() {
        assertTrue(VideoPolicy.answerSupportsLevel("m=video 9 UDP/TLS/RTP/SAVPF 96\r\na=fmtp:96 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e034\r\n", 52));
        assertTrue(VideoPolicy.answerSupportsLevel("m=video 9 UDP/TLS/RTP/SAVPF 96\r\na=fmtp:96 profile-level-id=42c034\r\n", 52));
        assertFalse(VideoPolicy.answerSupportsLevel("m=video 9 UDP/TLS/RTP/SAVPF 96\r\na=fmtp:96 profile-level-id=42e01f\r\n", 52));
        assertFalse(VideoPolicy.answerSupportsLevel("m=video 0 UDP/TLS/RTP/SAVPF 96\r\na=fmtp:96 profile-level-id=42e034\r\n", 52));
        assertFalse(VideoPolicy.answerSupportsLevel("m=audio 9 UDP/TLS/RTP/SAVPF 96\r\na=fmtp:96 profile-level-id=42e034\r\n", 52));
    }
}

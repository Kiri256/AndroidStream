package dev.androidstream;

import org.webrtc.*;
import org.json.JSONObject;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;

/** Adapts encoded root-process H.264/H.265 to WebRTC; no second video encoding. */
final class RootVideoEncoder implements VideoEncoder {
    private final CaptureBackend root;
    private final VideoSource source;
    private final int width, height;
    private final float fps;
    private final String codecName;
    private final String videoCodec;
    private final long frameIntervalNs;
    private final java.util.function.Consumer<String> failure;
    private volatile Callback callback;
    private JavaI420Buffer clockBuffer;
    private final EncodedFrameQueue pending = new EncodedFrameQueue();
    private final AtomicLong timestamp = new AtomicLong();
    private final VideoPacer pacer = new VideoPacer();
    private ExecutorService delivery;
    private final EncodedBufferPool buffers = new EncodedBufferPool();
    private volatile int targetBitrate;
    private long lastSubmitted;
    private long lastKeyRequest;
    RootVideoEncoder(CaptureBackend root, VideoSource source, dev.androidstream.root.VideoCodecs.Selection video,
                     java.util.function.Consumer<String> failure) {
        this.root = root; this.source = source; width = video.width(); height = video.height();
        fps = video.fps(); codecName = video.codec().getName(); videoCodec = video.codecName(); frameIntervalNs = VideoPolicy.frameIntervalNs(fps);
        this.failure = failure;
    }
    boolean started() { return callback != null; }
    @Override public synchronized VideoCodecStatus initEncode(Settings s, Callback c) {
        callback = c;
        try {
            clockBuffer = JavaI420Buffer.allocate(width, height);
            delivery = Executors.newSingleThreadExecutor(r -> new Thread(r, "encoded-video-pacer"));
            delivery.execute(this::deliver);
            targetBitrate = VideoPolicy.initialBitrate(s.startBitrate);
            root.command(new JSONObject().put("type", "start").put("width", width).put("height", height)
                    .put("fps", fps).put("bitrate", targetBitrate).put("codec", codecName).put("videoCodec", videoCodec));
        } catch (Exception e) { release(); failure.accept("视频编码初始化失败"); return VideoCodecStatus.ERROR; }
        return VideoCodecStatus.OK;
    }
    void packet(byte[] packet) {
        if (callback == null || packet.length < 13) return;
        ByteBuffer header = ByteBuffer.wrap(packet);
        boolean key = header.getInt() == 1;
        long pts = header.getLong();
        if (pacer.offer(packet, pts, key, System.nanoTime())) requestKey();
    }
    private void deliver() {
        try {
            while (callback != null && !Thread.currentThread().isInterrupted()) {
                VideoPacer.Frame next = pacer.take();
                // Match the display mode rather than imposing a fixed 60 FPS submission ceiling.
                long remaining;
                while ((remaining = frameIntervalNs - (System.nanoTime() - lastSubmitted)) > 0) {
                    LockSupport.parkNanos(remaining);
                    if (Thread.currentThread().isInterrupted()) return;
                }
                if (callback == null) return;
                lastSubmitted = System.nanoTime();
                submit(next.packet());
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (Exception e) {
            android.util.Log.e("AndroidStream", "Video pacing failed", e);
            if (callback != null) failure.accept("视频交接失败，请重新连接");
        }
    }
    private void submit(byte[] packet) {
        ByteBuffer header = ByteBuffer.wrap(packet);
        boolean key = header.getInt() == 1;
        header.getLong();
        long ns = Math.max(timestamp.get() + 1000, TimestampAligner.getRtcTimeNanos() / 1000 * 1000);
        timestamp.set(ns);
        EncodedFrameQueue.Delivery added = pending.add(ns, packet, 12, key);
        if (added.requestKey()) requestKey();
        if (added.frame() == null) return;
        JavaI420Buffer buffer;
        synchronized (this) {
            if (callback == null || clockBuffer == null) return;
            buffer = clockBuffer;
            buffer.retain();
        }
        VideoFrame frame = new VideoFrame(buffer, 0, ns);
        try { source.getCapturerObserver().onFrameCaptured(frame); } finally { frame.release(); }
    }
    @Override public VideoCodecStatus encode(VideoFrame frame, EncodeInfo info) {
        Callback target = callback;
        if (target == null) return VideoCodecStatus.OK;
        EncodedFrameQueue.Delivery delivery = pending.take(frame.getTimestampNs());
        if (delivery.requestKey()) requestKey();
        EncodedFrameQueue.Frame packet = delivery.frame();
        if (packet == null) return VideoCodecStatus.OK;
        // A keyframe already in hand satisfies the request; don't generate another IDR.
        if (!packet.key()) for (EncodedImage.FrameType type : info.frameTypes) {
            if (type == EncodedImage.FrameType.VideoFrameKey) requestKey();
        }
        ByteBuffer buffer = buffers.acquire(packet.bytes().length - packet.offset());
        buffer.put(packet.bytes(), packet.offset(), packet.bytes().length - packet.offset()).flip();
        // JNI reads capacity, not limit: the view must exclude stale bytes in a reused buffer.
        EncodedImage image = EncodedImage.builder().setBuffer(buffer.slice(), () -> buffers.recycle(buffer)).setEncodedWidth(width).setEncodedHeight(height)
                .setCaptureTimeNs(frame.getTimestampNs()).setRotation(0)
                .setFrameType(packet.key() ? EncodedImage.FrameType.VideoFrameKey : EncodedImage.FrameType.VideoFrameDelta).createEncodedImage();
        try {
            VideoEncoder.CodecSpecificInfo codecInfo = "H265".equals(videoCodec)
                    ? new VideoEncoder.CodecSpecificInfoH265()
                    : "AV1".equals(videoCodec) ? new VideoEncoder.CodecSpecificInfoAV1()
                    : new VideoEncoder.CodecSpecificInfoH264();
            target.onEncodedFrame(image, codecInfo);
        } finally { image.release(); }
        return VideoCodecStatus.OK;
    }
    @Override public synchronized VideoCodecStatus release() {
        callback = null; pending.clear();
        if (delivery != null) delivery.shutdownNow();
        pacer.clear();
        root.command("{\"type\":\"stopVideo\"}");
        if (clockBuffer != null) { clockBuffer.release(); clockBuffer = null; }
        return VideoCodecStatus.OK;
    }
    @Override public VideoCodecStatus setRateAllocation(BitrateAllocation bitrate, int framerate) {
        int next = VideoPolicy.allocatedBitrate(bitrate.getSum());
        if (Math.abs((long) next - targetBitrate) > Math.max(100000, targetBitrate / 10)) {
            targetBitrate = next;
            root.command("{\"type\":\"bitrate\",\"value\":" + next + "}");
        }
        return VideoCodecStatus.OK;
    }
    @Override public ScalingSettings getScalingSettings() { return ScalingSettings.OFF; }
    @Override public String getImplementationName() { return "Root-MediaCodec-Surface-" + videoCodec; }
    @Override public boolean isHardwareEncoder() { return true; }
    private synchronized void requestKey() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastKeyRequest < 250) return;
        lastKeyRequest = now;
        root.command("{\"type\":\"keyframe\"}");
    }
}

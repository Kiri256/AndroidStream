package dev.androidstream;

import android.content.Context;
import org.json.JSONObject;
import org.webrtc.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

final class RtcSession implements AutoCloseable {
    interface Events { void signal(JSONObject message); void error(String text); }
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64));
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor();
    private JavaI420Buffer seedBuffer;
    private final CaptureBackend root;
    private final Events events;
    private PeerConnectionFactory factory;
    private PeerConnection peer;
    private VideoSource source;
    private VideoTrack track;
    private volatile RootVideoEncoder encoder;
    private final List<DataChannel> channels = new ArrayList<>();
    private final int width, height;
    private final float fps;
    private final dev.androidstream.root.VideoCodecs.Selection video;
    private volatile boolean closed;
    private boolean remoteReady;
    private final List<IceCandidate> remoteCandidates = new ArrayList<>();
    private long inputWindow = System.nanoTime();
    private int inputs;
    private static boolean initialized;
    RtcSession(CaptureBackend root, JSONObject geometry, String codecName, Events events) throws Exception {
        this.root = root; this.events = events;
        float displayRate = VideoPolicy.refreshRate(geometry.getDouble("refreshRate"));
        video = dev.androidstream.root.VideoCodecs.nativeDisplay(geometry.getInt("width"), geometry.getInt("height"), displayRate, codecName);
        fps = video.fps();
        width = video.width(); height = video.height();
    }
    // Publish the session before initialization so failures use the same drain barrier as normal teardown.
    static synchronized void initializeRuntime(Context context, String nativeLibrary) {
        if (initialized) return;
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
                .setNativeLibraryLoader(name -> { System.load(nativeLibrary); return true; }).createInitializationOptions());
        initialized = true;
    }
    void initialize(Context context, String nativeLibrary) throws Exception {
        // An unset sender max invokes M144's 2.5 Mbps resolution default, not an unlimited budget.
        // Query the same hardware encoder as Root and use its declared bitrate range.
        android.util.Range<Integer> bitrateRange = video.codec().getCapabilitiesForType(video.mime()).getVideoCapabilities().getBitrateRange();
        int startBitrate = VideoPolicy.startBitrate(bitrateRange.getLower(), bitrateRange.getUpper());
        int maxBitrate = VideoPolicy.maxBitrate(bitrateRange.getLower(), bitrateRange.getUpper());
        initializeRuntime(context, nativeLibrary);
        // No ordinary App Context or foreground Activity is available in app_process.
        // Native network enumeration still gathers LAN ICE candidates; do not use Android's App network monitor.
        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        options.disableNetworkMonitor = true;
        // Dropping raw frames is unsafe after MediaCodec has already encoded references.
        // M144 screenshare defaults to a 2875 ms pacer window. Keep ALR probing,
        // but drain bursts earlier; this is a tuning target, not a hard delay cap.
        factory = PeerConnectionFactory.builder().setOptions(options).setFieldTrials("WebRTC-FrameDropper/Disabled/"
                + "WebRTC-ProbingScreenshareBwe/1.5,40,80,40,-60,3/").setVideoEncoderFactory(new VideoEncoderFactory() {
            @Override public VideoEncoder createEncoder(VideoCodecInfo info) {
                if (!info.name.equalsIgnoreCase(video.codecName())) return null;
                encoder = new RootVideoEncoder(root, source, video, events::error); return encoder;
            }
            @Override public VideoCodecInfo[] getSupportedCodecs() {
                if ("H264".equals(video.codecName()))
                    return new VideoCodecInfo[]{new VideoCodecInfo("H264", Map.of("level-asymmetry-allowed", "1",
                            "packetization-mode", "1", "profile-level-id", video.profileLevelId()), List.of())};
                if ("H265".equals(video.codecName()))
                    return new VideoCodecInfo[]{new VideoCodecInfo("H265", Map.of("profile-id", "1",
                            "level-id", Integer.toString(video.levelIdc()), "tx-mode", "SRST"), List.of())};
                return new VideoCodecInfo[]{new VideoCodecInfo("AV1", Map.of("profile", "0", "level-idx",
                        Integer.toString(Math.max(0, dev.androidstream.root.VideoCodecs.av1LevelIndex(video.codecLevel()))),
                        "tier", "0"), List.of())};
            }
        }).createPeerConnectionFactory();
        // Root packet lookup uses the original capture timestamp; don't realign it in JNI.
        source = factory.createVideoSource(true, false);
        // These frames carry already-encoded access units: dropping/scaling them as raw
        // camera frames would break H.264 reference dependencies and copy dummy pixels.
        source.setVideoProcessor(new VideoProcessor() {
            private VideoSink sink;
            public void setSink(VideoSink value) { sink = value; }
            public void onCapturerStarted(boolean success) {}
            public void onCapturerStopped() {}
            public void onFrameCaptured(VideoFrame frame) { if (sink != null) sink.onFrame(frame); }
            @Override public void onFrameCaptured(VideoFrame frame, FrameAdaptationParameters parameters) { onFrameCaptured(frame); }
        });
        source.getCapturerObserver().onCapturerStarted(true);
        track = factory.createVideoTrack("screen", source);
        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(Collections.emptyList());
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        config.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        peer = factory.createPeerConnection(config, new PeerConnection.Observer() {
            public void onSignalingChange(PeerConnection.SignalingState s) {}
            public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
                if (!closed && (s == PeerConnection.IceConnectionState.FAILED || s == PeerConnection.IceConnectionState.DISCONNECTED)) {
                    root.command("{\"type\":\"cancelAll\"}");
                    root.command("{\"type\":\"screenRestore\"}");
                }
            }
            public void onIceConnectionReceivingChange(boolean b) {}
            public void onIceGatheringChange(PeerConnection.IceGatheringState s) {}
            public void onIceCandidate(IceCandidate c) {
                try { events.signal(new JSONObject().put("type", "candidate").put("candidate", new JSONObject()
                        .put("candidate", c.sdp).put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex))); }
                catch (Exception e) { events.error("ICE serialization failed"); }
            }
            public void onIceCandidatesRemoved(IceCandidate[] c) {}
            public void onAddStream(MediaStream s) {}
            public void onRemoveStream(MediaStream s) {}
            public void onDataChannel(DataChannel channel) {
                if (!channel.label().equals("control") && !channel.label().equals("motion")) { channel.close(); channel.dispose(); return; }
                synchronized (channels) {
                    if (closed || channels.size() >= 2 || channels.stream().anyMatch(c -> c.label().equals(channel.label()))) {
                        channel.close(); channel.dispose(); return;
                    }
                    channels.add(channel);
                channel.registerObserver(new DataChannel.Observer() {
                    public void onBufferedAmountChange(long l) {}
                    public void onStateChange() {
                        if (!closed && channel.state() != DataChannel.State.OPEN) {
                            root.command("{\"type\":\"cancelAll\"}");
                            if (channel.label().equals("control")) root.command("{\"type\":\"screenRestore\"}");
                        }
                    }
                    public void onMessage(DataChannel.Buffer b) {
                        if (closed) return;
                        if (b.binary || b.data.remaining() > Wire.MAX_COMMAND) { root.command("{\"type\":\"cancelAll\"}"); return; }
                        long now = System.nanoTime();
                        synchronized (RtcSession.this) {
                            if (now - inputWindow > 1000000000L) { inputs = 0; inputWindow = now; }
                            if (++inputs > 500) { root.command("{\"type\":\"cancelAll\"}"); return; }
                        }
                        byte[] bytes = new byte[b.data.remaining()]; b.data.get(bytes);
                        try {
                            JSONObject json = new JSONObject(StandardCharsets.UTF_8.newDecoder()
                                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString());
                            String type = json.getString("type");
                            if (!Set.of("touch", "key", "text", "cancelAll", "heartbeat", "screen").contains(type)) return;
                            if (type.equals("screen") && !(json.get("off") instanceof Boolean)) return;
                            if (channel.label().equals("motion") && (!type.equals("touch") || !json.optString("action").equals("move"))) return;
                            if (json.toString().getBytes(StandardCharsets.UTF_8).length > Wire.MAX_COMMAND) throw new IllegalArgumentException("Command too large");
                            root.command(json);
                        } catch (Exception e) { root.command("{\"type\":\"cancelAll\"}"); }
                    }
                });
                }
            }
            public void onRenegotiationNeeded() {}
        });
        if (peer == null) throw new IllegalStateException("WebRTC creation failed");
        if (!peer.setBitrate(null, startBitrate, maxBitrate)) throw new IllegalStateException("WebRTC 带宽参数设置失败");
        org.webrtc.RtpSender sender = peer.addTrack(track, List.of("screen"));
        RtpParameters params = sender.getParameters();
        for (RtpParameters.Encoding encoding : params.encodings) {
            encoding.maxBitrateBps = maxBitrate;
            encoding.minBitrateBps = VideoPolicy.codecBitrate(1000000, bitrateRange.getLower(), bitrateRange.getUpper());
            encoding.maxFramerate = (int) Math.ceil(fps);
        }
        params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION;
        if (!sender.setParameters(params)) throw new IllegalStateException("WebRTC 视频参数设置失败");
        // WebRTC creates/initializes its encoder only after receiving a source frame.
        // Seed the pipeline until root-produced frames take over; no pixels are encoded here.
        seedBuffer = JavaI420Buffer.allocate(width, height);
        clock.scheduleWithFixedDelay(() -> {
            if (closed || encoder != null && encoder.started()) return;
            synchronized (this) {
                if (closed || seedBuffer == null) return;
                seedBuffer.retain();
                VideoFrame frame = new VideoFrame(seedBuffer, 0, System.nanoTime());
                try { source.getCapturerObserver().onFrameCaptured(frame); } finally { frame.release(); }
            }
        }, 0, 250, TimeUnit.MILLISECONDS);
    }
    void offer(String sdp) {
        execute(() -> peer.setRemoteDescription(new SdpAdapter(events) {
            @Override public void onSetSuccess() {
                execute(() -> {
                    remoteReady = true;
                    for (IceCandidate candidate : remoteCandidates) peer.addIceCandidate(candidate);
                    remoteCandidates.clear();
                    peer.createAnswer(new SdpAdapter(events) {
                    @Override public void onCreateSuccess(SessionDescription answer) {
                        if (!VideoPolicy.answerSupportsCodec(answer.description, video.codecName())) {
                            events.error("客户端未协商手机所选的 " + video.codecName() + " 视频编码");
                            return;
                        }
                        if ("H264".equals(video.codecName()) && !VideoPolicy.answerSupportsLevel(answer.description, video.levelIdc())) {
                            events.error("浏览器协商的 H.264 Level 不支持手机原生规格，请检查浏览器兼容性");
                            return;
                        }
                        execute(() -> peer.setLocalDescription(new SdpAdapter(events) {
                            @Override public void onSetSuccess() {
                                try { events.signal(new JSONObject().put("type", "answer").put("sdp", answer.description)); }
                                catch (Exception e) { events.error("Answer serialization failed"); }
                            }
                        }, answer));
                    }
                }, new MediaConstraints());
                });
            }
        }, new SessionDescription(SessionDescription.Type.OFFER, sdp)));
    }
    void candidate(JSONObject c) throws Exception {
        IceCandidate candidate = new IceCandidate(c.getString("sdpMid"), c.getInt("sdpMLineIndex"), c.getString("candidate"));
        execute(() -> {
            if (remoteReady) peer.addIceCandidate(candidate);
            else if (remoteCandidates.size() < 64) remoteCandidates.add(candidate);
        });
    }
    void video(byte[] bytes) { RootVideoEncoder e = encoder; if (e != null && !closed) e.packet(bytes); }
    private synchronized void execute(Runnable action) {
        if (closed) return;
        try { worker.execute(() -> { if (!closed) { try { action.run(); } catch (Exception e) { events.error("WebRTC: " + e); } } }); }
        catch (RejectedExecutionException e) { events.error("WebRTC 工作队列过载，请重新连接"); }
    }
    @Override public void close() { if (!closed) close(() -> {}); }
    synchronized void close(Runnable finished) {
        if (closed) throw new IllegalStateException("Session already closing");
        closed = true;
        clock.shutdownNow();
        synchronized (this) { if (seedBuffer != null) { seedBuffer.release(); seedBuffer = null; } }
        root.command("{\"type\":\"cancelAll\"}");
        // Pending signaling must not prevent resource teardown from entering the bounded queue.
        ((ThreadPoolExecutor) worker).getQueue().clear();
        worker.execute(() -> {
            try {
                List<DataChannel> closingChannels;
                synchronized (channels) {
                    closingChannels = new ArrayList<>(channels);
                    channels.clear();
                }
                // Native close can wait for callbacks; never hold their admission lock here.
                for (DataChannel c : closingChannels) { dispose(c::unregisterObserver); dispose(c::close); dispose(c::dispose); }
                if (peer != null) { dispose(peer::close); dispose(peer::dispose); }
                if (track != null) dispose(track::dispose);
                if (source != null) { dispose(() -> source.getCapturerObserver().onCapturerStopped()); dispose(source::dispose); }
                if (factory != null) dispose(factory::dispose);
            } finally { root.command("{\"type\":\"stopVideo\"}"); finished.run(); }
        });
        worker.shutdown();
    }
    private static void dispose(Runnable action) {
        try { action.run(); }
        catch (RuntimeException e) { android.util.Log.w("AndroidStream", "WebRTC cleanup failed", e); }
    }
    private static class SdpAdapter implements SdpObserver {
        private final Events events;
        SdpAdapter(Events events) { this.events = events; }
        public void onCreateSuccess(SessionDescription s) {}
        public void onSetSuccess() {}
        public void onCreateFailure(String e) { events.error("SDP create: " + e); }
        public void onSetFailure(String e) { events.error("SDP set: " + e); }
    }
}

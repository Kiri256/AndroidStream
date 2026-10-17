package dev.androidstream.root;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.view.Surface;
import dev.androidstream.CaptureBackend;
import dev.androidstream.Wire;
import dev.androidstream.VideoPolicy;
import org.json.JSONObject;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Root-owned capture and input backend; media stays in this process. */
public final class RootCapture implements CaptureBackend, AutoCloseable {
    private volatile Listener listener;
    private final java.util.concurrent.ArrayBlockingQueue<String> commands = new java.util.concurrent.ArrayBlockingQueue<>(128);
    private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
    private DisplayAccess display;
    private InputAccess input;
    private ScreenPower screen;
    private volatile int[] geometry;
    private final DisplayMode displayMode;
    private volatile int generation = 1;
    private volatile boolean running = true, capturing;
    private final Object inputLock = new Object();
    private volatile int requestedBitrate;
    private final java.util.concurrent.atomic.AtomicBoolean keyframeRequested = new java.util.concurrent.atomic.AtomicBoolean();
    private Thread videoThread;
    private volatile boolean recording;
    private Thread audioThread;
    private final Object audioLock = new Object();
    private android.media.AudioRecord audioRecord;

    public RootCapture() throws Exception {
        if (Process.myUid() != 0) throw new SecurityException("Root required");
        display = new DisplayAccess();
        input = new InputAccess();
        geometry = display.geometry();
        displayMode = new DisplayMode(geometry);
        screen = new ScreenPower(display::setScreenOff);
    }
    public android.content.Context context() throws Exception { return display.context(); }
    public void setListener(Listener value) { listener = value; }
    @Override public void command(String text) {
        if (!running) return;
        if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > Wire.MAX_COMMAND || !commands.offer(text)) {
            requestStop();
            error("Capture: command queue overloaded");
        }
    }
    public void requestStop() {
        synchronized (inputLock) {
            running = false;
            if (input != null) input.cancel();
        }
        commands.clear(); commands.offer("{\"type\":\"shutdown\"}");
        restoreScreen();
    }
    public void runCommands() throws Exception {
        Thread monitor = new Thread(() -> {
            while (running) {
                try {
                    input.watchdog();
                    if (screen.check(SystemClock.uptimeMillis())) sendScreen();
                    if (!capturing) checkGeometry();
                    Thread.sleep(250);
                } catch (Exception e) { error("Display monitor: " + message(e)); }
            }
        }, "root-watchdog");
        monitor.setDaemon(true); monitor.start();
        try {
            while (running) {
                JSONObject c = new JSONObject(commands.take());
                String type = c.getString("type");
                try {
                    switch (type) {
                        case "start":
                            stopVideo();
                            if (!running) break;
                            int w = c.getInt("width"), h = c.getInt("height");
                            float fps = VideoPolicy.refreshRate(c.getDouble("fps"));
                            int bitrate = c.getInt("bitrate");
                            String codecName = c.getString("codec");
                            if (w < 1 || h < 1 || bitrate < 100000)
                                throw new IllegalArgumentException("Invalid codec settings");
                            capturing = true;
                            requestedBitrate = bitrate;
                            keyframeRequested.set(false);
                            videoThread = new Thread(() -> video(w, h, fps, bitrate, codecName), "root-encoder");
                            videoThread.start();
                            break;
                        case "stopVideo": stopVideo(); input.cancel(); break;
                        case "startAudio": startAudio(); break;
                        case "stopAudio": stopAudio(); break;
                        case "screen":
                            if (!(c.get("off") instanceof Boolean)) throw new IllegalArgumentException("Invalid screen state");
                            synchronized (inputLock) {
                                if (!running) break;
                                try { screen.setOff(c.getBoolean("off"), SystemClock.uptimeMillis()); }
                                catch (Exception e) { error("Screen: " + message(e)); }
                            }
                            sendScreen();
                            break;
                        case "screenRestore": restoreScreen(); sendScreen(); break;
                        case "keyframe":
                            keyframeRequested.set(true);
                            break;
                        case "bitrate":
                            requestedBitrate = Math.max(100000, c.getInt("value"));
                            break;
                        case "shutdown": running = false; break;
                        default: synchronized (inputLock) {
                            if (running) {
                                if (type.equals("heartbeat")) screen.heartbeat(SystemClock.uptimeMillis());
                                input.command(c, geometry, generation);
                            }
                        }
                    }
                } catch (Exception e) { input.cancel(); error(message(e)); }
            }
        } finally { close(); }
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        requestStop();
        stopVideo();
        stopAudio();
    }
    private void packet(int type, byte[] data) throws IOException {
        Listener target = listener;
        if (target != null) target.packet(type, data);
    }
    private void error(String text) {
        try { packet(Wire.ERROR, text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); } catch (IOException ignored) {}
    }
    private void restoreScreen() {
        try { if (screen != null) screen.restore(); }
        catch (Exception e) { error("Screen: 恢复点亮失败，请使用手机电源键"); }
    }
    private void sendScreen() throws Exception {
        packet(Wire.SCREEN, new JSONObject().put("type", "screen").put("off", screen.isOff())
                .toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static String message(Throwable e) {
        while (e.getCause() != null && e.getCause() != e) e = e.getCause();
        if (e instanceof org.json.JSONException) return "Invalid command fields";
        return e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
    }
    public synchronized JSONObject deviceInfo() throws Exception {
        return new JSONObject().put("width", geometry[0]).put("height", geometry[1]).put("rotation", geometry[2])
                .put("refreshRate", Float.intBitsToFloat(geometry[4]))
                .put("generation", generation).put("sdk", android.os.Build.VERSION.SDK_INT)
                .put("device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL).put("uid", Process.myUid())
                .put("screenOff", screen.isOff());
    }
    private void sendInfo() throws Exception {
        packet(Wire.INFO, deviceInfo().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private synchronized boolean checkGeometry() throws Exception {
        int[] next = displayMode.update(display.geometry(), SystemClock.uptimeMillis());
        if (Arrays.equals(next, geometry)) return false;
        synchronized (inputLock) {
            boolean coordinatesChanged = false;
            for (int i = 0; i < 4; i++) if (next[i] != geometry[i]) coordinatesChanged = true;
            if (coordinatesChanged) { input.cancel(); generation++; }
            geometry = next;
        }
        sendInfo();
        return true;
    }
    private void stopVideo() {
        capturing = false;
        Thread thread = videoThread;
        if (thread != null && thread != Thread.currentThread()) {
            try { thread.join(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            // A wedged encoder must not overlap a new capture session.
            if (thread.isAlive()) { requestStop(); }
        }
        videoThread = null;
    }
    private void startAudio() {
        stopAudio();
        if (!running) return;
        recording = true;
        audioThread = new Thread(() -> {
            android.media.AudioRecord record = null;
            try {
                // REMOTE_SUBMIX (8) captures system playback, not the microphone.
                record = AudioAccess.create();
                if (record.getState() != android.media.AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("REMOTE_SUBMIX initialization failed");
                synchronized (audioLock) {
                    if (!running || !recording) return;
                    audioRecord = record;
                    record.startRecording();
                }
                byte[] pcm = new byte[3840]; // 20 ms, stereo PCM16 LE at 48 kHz
                while (running && recording) {
                    int read = record.read(pcm, 0, pcm.length, android.media.AudioRecord.READ_BLOCKING);
                    if (!running || !recording) break;
                    if (read <= 0 || read % 4 != 0) throw new IOException("AudioRecord read: " + read);
                    packet(Wire.AUDIO, read == pcm.length ? pcm : Arrays.copyOf(pcm, read));
                }
            } catch (Throwable e) { if (recording && running) error("Audio: " + message(e)); }
            finally {
                synchronized (audioLock) {
                    recording = false;
                    audioRecord = null;
                    if (record != null) { try { record.stop(); } catch (Exception ignored) {} record.release(); }
                }
            }
        }, "root-audio");
        audioThread.start();
    }
    private void stopAudio() {
        synchronized (audioLock) {
            recording = false;
            // Stop unblocks READ_BLOCKING before joining the capture thread.
            if (audioRecord != null) try { audioRecord.stop(); } catch (Exception ignored) {}
        }
        if (audioThread != null) {
            try { audioThread.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (audioThread.isAlive()) { requestStop(); }
            audioThread = null;
        }
    }
    private void video(int width, int height, float fps, int bitrate, String codecName) {
        MediaCodec codec = null;
        Surface surface = null;
        AutoCloseable mirror = null;
        try {
            VideoCodecs.Selection selection = VideoCodecs.select(width, height, fps, codecName);
            MediaCodecInfo selected = selection.codec();
            String mime = "video/avc";
            android.util.Range<Integer> encoderBitrateRange = selected.getCapabilitiesForType(mime).getVideoCapabilities().getBitrateRange();
            codec = MediaCodec.createByCodecName(selected.getName());
            MediaFormat format = MediaFormat.createVideoFormat(mime, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            int appliedBitrate = VideoPolicy.codecBitrate(bitrate, encoderBitrateRange.getLower(), encoderBitrateRange.getUpper());
            format.setInteger(MediaFormat.KEY_BIT_RATE, appliedBitrate);
            format.setFloat(MediaFormat.KEY_FRAME_RATE, fps);
            // Periodic IDRs at 1 Hz produce large RTP bursts on detailed UI frames.
            // PLI/loss recovery still requests an IDR immediately when needed.
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5);
            format.setInteger(MediaFormat.KEY_PROFILE, selection.codecProfile());
            format.setInteger(MediaFormat.KEY_LEVEL, selection.codecLevel());
            format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);
            format.setInteger(MediaFormat.KEY_LATENCY, 1);
            MediaCodecInfo.EncoderCapabilities rateControl = selected.getCapabilitiesForType(mime).getEncoderCapabilities();
            boolean cbr = rateControl.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
            if (cbr)
                format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
            format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps);
            format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100000);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            surface = codec.createInputSurface();
            mirror = display.mirror(surface, width, height, geometry);
            codec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            byte[] config = new byte[0];
            long lastCheck = 0;
            while (running && capturing) {
                // MediaCodec is owned exclusively by this thread, including dynamic updates.
                int nextBitrate = VideoPolicy.codecBitrate(requestedBitrate, encoderBitrateRange.getLower(), encoderBitrateRange.getUpper());
                if (nextBitrate != appliedBitrate || keyframeRequested.get()) {
                    Bundle update = new Bundle();
                    if (nextBitrate != appliedBitrate) update.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, nextBitrate);
                    if (keyframeRequested.getAndSet(false)) update.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                    codec.setParameters(update);
                    appliedBitrate = nextBitrate;
                }
                if (SystemClock.uptimeMillis() - lastCheck > 250) {
                    lastCheck = SystemClock.uptimeMillis();
                    if (checkGeometry()) break;
                }
                int index = codec.dequeueOutputBuffer(info, 50000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat output = codec.getOutputFormat();
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    for (String key : new String[]{"csd-0", "csd-1"}) {
                        ByteBuffer csd = output.getByteBuffer(key);
                        if (csd != null) { byte[] bytes = new byte[csd.remaining()]; csd.get(bytes); b.write(bytes); }
                    }
                    config = b.toByteArray();
                } else if (index >= 0) {
                    try {
                        ByteBuffer buffer = codec.getOutputBuffer(index);
                        if (buffer == null || info.size == 0) continue;
                        buffer.position(info.offset); buffer.limit(info.offset + info.size);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            config = new byte[info.size]; buffer.get(config); continue;
                        }
                        boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                        int extra = key ? config.length : 0;
                        if ((long) info.size + 12 + extra > Wire.MAX_VIDEO) throw new IOException("Encoded frame too large");
                        byte[] packet = new byte[12 + info.size + extra];
                        ByteBuffer header = ByteBuffer.wrap(packet);
                        header.putInt(key ? 1 : 0).putLong(info.presentationTimeUs);
                        if (key) header.put(config);
                        buffer.get(packet, 12 + extra, info.size);
                        packet(Wire.VIDEO, packet);
                    } finally { codec.releaseOutputBuffer(index, false); }
                }
            }
        } catch (Throwable e) {
            error("Capture: H.264 Surface 配置失败（" + width + "×" + height + " @ "
                    + String.format(java.util.Locale.ROOT, "%.2f", fps) + " Hz，" + codecName + "）：" + message(e));
        }
        finally {
            capturing = false;
            try { if (mirror != null) mirror.close(); } catch (Exception ignored) {}
            try { if (codec != null) codec.stop(); } catch (Exception ignored) {}
            try { if (surface != null) surface.release(); }
            finally { if (codec != null) codec.release(); }
        }
    }
}

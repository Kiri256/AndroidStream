package dev.androidstream;

import android.content.Context;
import dev.androidstream.root.RootCapture;
import org.json.JSONObject;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

final class RootSessionServer implements CaptureBackend.Listener, LocalWebServer.Events, AutoCloseable {
    private final ThreadPoolExecutor control = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64));
    private final RootCapture root;
    private final Context context;
    private final String nativeLibrary;
    private final Runnable stop;
    private LocalWebServer server;
    private volatile RtcSession rtc;
    private volatile LocalWebServer.Client active;
    private volatile JSONObject info;
    private volatile String address = "", status = "正在启动";
    private volatile boolean closing;
    private int mediaGeneration = 1;
    private boolean reconfiguring;
    private int drainingSessions;
    private boolean audioEnabled;

    RootSessionServer(Context context, RootCapture root, String apk, String nativeLibrary, Runnable stop) throws Exception {
        this.context = context; this.root = root; this.nativeLibrary = nativeLibrary; this.stop = stop;
        info = root.deviceInfo();
        InetAddress ip = findAddress();
        if (ip == null) throw new IllegalStateException("没有局域网 IPv4 地址，请先连接 Wi-Fi");
        // Read only the three fixed routes from the installed APK, never arbitrary file paths.
        Map<String, byte[]> assets = new HashMap<>();
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apk)) {
            for (String name : List.of("index.html", "app.js", "style.css")) {
                try (java.io.InputStream in = zip.getInputStream(zip.getEntry("assets/" + name));
                     java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                    assets.put(name, out.toByteArray());
                }
            }
        }
        root.setListener(this);
        server = new LocalWebServer(name -> assets.get(name), ip, 8080, this);
        address = "http://" + ip.getHostAddress() + ":8080";
        status = "Root 服务就绪，电脑打开网页即可连接";
    }
    JSONObject snapshot() throws Exception {
        return new JSONObject().put("type", "state").put("ready", !closing).put("address", address)
                .put("status", status).put("device", info);
    }
    private InetAddress findAddress() throws Exception {
        InetAddress fallback = null;
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface ni = interfaces.nextElement();
            if (!ni.isUp() || ni.isLoopback()) continue;
            Enumeration<InetAddress> addresses = ni.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress ip = addresses.nextElement();
                if (!(ip instanceof Inet4Address) || !ip.isSiteLocalAddress()) continue;
                if (ni.getName().startsWith("wlan") || ni.getName().startsWith("ap")) return ip;
                if (ni.getName().startsWith("eth")) fallback = ip;
            }
        }
        return fallback;
    }
    @Override public void connected(LocalWebServer.Client client) { enqueue(() -> {
        if (active != null) { send(client, "error", "已有电脑连接，请先断开原连接"); client.closeAfterWrites(); return; }
        if (closing || info == null) { send(client, "error", "Root 尚未就绪，请稍后重新连接"); client.closeAfterWrites(); return; }
        active = client;
        status = "电脑已连接";
        if (drainingSessions == 0) sendReady(client);
    }); }
    private void sendReady(LocalWebServer.Client client) {
        try {
            java.util.List<String> videoCodecs = dev.androidstream.root.VideoCodecs.supportedNativeCodecs(
                    info.getInt("width"), info.getInt("height"), (float) info.getDouble("refreshRate"));
            client.text(new JSONObject().put("type", "ready").put("device", info).put("videoCodecs", new org.json.JSONArray(videoCodecs))
                    .put("mediaGeneration", mediaGeneration).toString());
        }
        catch (Exception e) { send(client, "error", "设备信息错误"); client.closeAfterWrites(); }
    }
    private void reconfigureMedia() throws Exception {
        LocalWebServer.Client client = active;
        if (client == null || reconfiguring) return;
        reconfiguring = true;
        int next = ++mediaGeneration;
        client.text(new JSONObject().put("type", "reconfigure").put("mediaGeneration", next).toString());
        root.command("{\"type\":\"cancelAll\"}");
        RtcSession old = rtc; rtc = null;
        if (old == null) {
            if (drainingSessions == 0) { reconfiguring = false; sendReady(client); }
        } else closeMedia(old);
    }
    private void closeMedia(RtcSession session) {
        drainingSessions++;
        session.close(() -> enqueue(() -> {
            drainingSessions--;
            if (drainingSessions != 0) return;
            reconfiguring = false;
            if (active != null) { sendReady(active); status = "正在恢复画面"; }
        }));
    }
    @Override public void message(LocalWebServer.Client c, String text) { enqueue(() -> {
        try {
            JSONObject j = new JSONObject(text);
            String type = j.getString("type");
            if ("ping".equals(type)) {
                send(c, "pong", "");
                return;
            }
            if (c != active) return;
            if (("offer".equals(type) || "candidate".equals(type))
                    && (reconfiguring || j.optInt("mediaGeneration", -1) != mediaGeneration)) return;
            switch (type) {
                case "offer":
                    if (rtc != null) { send(c, "error", "会话已存在，请断开后重连"); return; }
                    final int epoch = mediaGeneration;
                    String videoCodec = j.optString("videoCodec", dev.androidstream.root.VideoCodecs.H264);
                    rtc = new RtcSession(root, info, videoCodec, new RtcSession.Events() {
                        public void signal(JSONObject signal) {
                            enqueue(() -> {
                                if (active != c || mediaGeneration != epoch) return;
                                try { c.text(signal.put("mediaGeneration", epoch).toString()); }
                                catch (Exception ignored) {}
                            });
                        }
                        public void error(String error) { enqueue(() -> {
                            if (active == c && mediaGeneration == epoch) failSession(error);
                        }); }
                    });
                    rtc.initialize(context, nativeLibrary);
                    rtc.offer(j.getString("sdp"));
                    break;
                case "candidate": if (rtc != null) rtc.candidate(j.getJSONObject("candidate")); break;
                case "audio":
                    boolean enabled = j.optBoolean("enabled");
                    if (enabled != audioEnabled) {
                        audioEnabled = enabled;
                        root.command(enabled ? "{\"type\":\"startAudio\"}" : "{\"type\":\"stopAudio\"}");
                    }
                    break;
                case "disconnect": c.close(); break;
                default: send(c, "error", "不支持的信令消息");
            }
        } catch (Exception e) {
            if (active == c) failSession(e instanceof org.json.JSONException ? "信令字段无效"
                    : e instanceof dev.androidstream.root.VideoCodecs.UnsupportedVideoException ? e.getMessage()
                    : "连接失败：" + e.getClass().getSimpleName());
            else { send(c, "error", "信令无效"); c.closeAfterWrites(); }
        }
    }); }
    @Override public void disconnected(LocalWebServer.Client c) { enqueue(() -> {
        if (active != c) return;
        endSession(); status = "电脑已断开，可重新连接";
    }); }
    private void endSession() {
        active = null;
        audioEnabled = false;
        root.command("{\"type\":\"screenRestore\"}");
        mediaGeneration++; reconfiguring = drainingSessions != 0;
        RtcSession old = rtc; rtc = null;
        if (old != null) closeMedia(old);
        if (root != null) {
            root.command("{\"type\":\"cancelAll\"}");
            root.command("{\"type\":\"stopAudio\"}");
        }
    }
    @Override public void packet(int type, byte[] bytes) {
        if (closing) return;
        if (type == Wire.VIDEO) {
            RtcSession session = rtc;
            if (session != null) session.video(bytes);
            return;
        }
        if (type == Wire.AUDIO) { LocalWebServer.Client client = active; if (client != null) client.audio(bytes); return; }
        enqueue(() -> {
            try {
                String text = new String(bytes, StandardCharsets.UTF_8);
                if (type == Wire.SCREEN) {
                    JSONObject state = new JSONObject(text);
                    info = new JSONObject(info.toString()).put("screenOff", state.getBoolean("off"));
                    if (active != null) active.text(state.toString());
                } else if (type == Wire.INFO) {
                    JSONObject previous = info; info = new JSONObject(text);
                    status = "Root 就绪，电脑打开网页即可连接";
                    if (previous != null && active != null && (previous.optInt("generation") != info.optInt("generation")
                            || previous.optDouble("refreshRate") != info.optDouble("refreshRate"))) {
                        reconfigureMedia();
                    }
                } else if (type == Wire.ERROR) {
                    status = text;
                    if (text.startsWith("Capture:")) failSession(text);
                    else {
                        if (text.startsWith("Audio:")) audioEnabled = false;
                        if (active != null) send(active, "error", text);
                    }
                    android.util.Log.w("AndroidStream", text);
                }
            } catch (Exception e) { status = "Root 数据无效：" + e.getMessage(); }
        });
    }
    private void failSession(String reason) {
        LocalWebServer.Client client = active;
        if (client != null) {
            try { client.text(new JSONObject().put("type", "error").put("fatal", true)
                    .put("message", reason).toString()); } catch (Exception ignored) {}
            client.closeAfterWrites();
        }
        endSession(); status = reason;
    }
    private void send(LocalWebServer.Client c, String type, String message) {
        try { c.text(new JSONObject().put("type", type).put("message", message).toString()); } catch (Exception ignored) {}
    }
    private void enqueue(Runnable action) {
        if (closing) return;
        try { control.execute(() -> { if (!closing) action.run(); }); }
        catch (RejectedExecutionException e) {
            android.util.Log.e("AndroidStream", "Control queue overloaded; stopping service");
            stop.run();
        }
    }
    @Override public void close() {
        closing = true;
        if (server != null) server.close();
        control.getQueue().clear();
        control.execute(this::endSession);
        control.shutdown();
        try { control.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}


package dev.androidstream;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import org.json.JSONObject;
import java.io.*;
import java.util.concurrent.TimeUnit;

/** Management only. Detaching the App never terminates the independent Root daemon. */
final class RootBridge implements AutoCloseable {
    interface Listener { void state(JSONObject state); void disconnected(String reason); }
    private final Context context;
    private final Listener listener;
    private final boolean autoStart;
    private volatile boolean closed;
    private volatile LocalSocket socket;
    private volatile boolean launchAttempted, ready;
    private final Thread reader;

    RootBridge(Context context, boolean autoStart, Listener listener) {
        this.context = context.getApplicationContext(); this.autoStart = autoStart; this.listener = listener;
        reader = new Thread(this::read, "root-manager-client");
    }
    void start() { reader.start(); }
    boolean pendingStart() { return launchAttempted && !ready; }
    private static String quote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
    static boolean absent(IOException error) {
        // Some Android LocalSocket implementations expose only strerror(), without an errno name.
        // Prefer a structured errno when present; permission and timeout failures must remain errors.
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
            if (cause instanceof android.system.ErrnoException) {
                int errno = ((android.system.ErrnoException) cause).errno;
                return errno == android.system.OsConstants.ENOENT || errno == android.system.OsConstants.ECONNREFUSED;
            }
        }
        cause = error;
        for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
            String message = String.valueOf(cause.getMessage()).toLowerCase(java.util.Locale.ROOT);
            if (message.contains("enoent") || message.contains("econnrefused")
                    || message.contains("no such file or directory") || message.contains("connection refused")) return true;
        }
        return false;
    }
    private static LocalSocket connect() throws IOException {
        LocalSocket result = new LocalSocket();
        try {
            result.connect(new LocalSocketAddress(DaemonProtocol.socketName(android.os.Process.myUid())));
            if (result.getPeerCredentials().getUid() != 0) throw new SecurityException("管理端不是 Root 进程");
            result.setSoTimeout(3000);
            return result;
        } catch (IOException | RuntimeException e) { result.close(); throw e; }
    }
    private String version() throws Exception {
        return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
    }
    private void launch() throws Exception {
        String apk = context.getApplicationInfo().sourceDir;
        File library = new File(context.getApplicationInfo().nativeLibraryDir, "libjingle_peerconnection_so.so");
        if (!library.isFile()) throw new IOException("未找到已安装的 WebRTC 原生库，请重新安装 APK");
        File logFile = new File(context.getFilesDir(), "root-daemon.log");
        // App owns this private file so it remains readable for development diagnostics.
        if (!logFile.exists() && !logFile.createNewFile()) throw new IOException("无法创建 Root 启动日志");
        String log = logFile.getAbsolutePath();
        // Ignore SIGHUP and detach all standard streams before su exits. No App-owned log pipe remains.
        String command = "(trap '' HUP; export CLASSPATH=" + quote(apk)
                + "; exec /system/bin/app_process /system/bin --nice-name=androidstream-root dev.androidstream.RootDaemon "
                + android.os.Process.myUid() + " " + quote(apk) + " " + quote(library.getAbsolutePath())
                + " " + quote(version()) + ") </dev/null >" + quote(log) + " 2>&1 &";
        java.lang.Process launcher = new ProcessBuilder("su", "-c", command).redirectErrorStream(true)
                .redirectOutput(new File("/dev/null")).start();
        try {
            if (!launcher.waitFor(20, TimeUnit.SECONDS)) throw new IOException("Root 授权超时");
            if (launcher.exitValue() != 0) throw new IOException("Root 启动被拒绝");
        } finally { launcher.destroy(); }
    }
    private void read() {
        boolean launched = false, seen = false, readySeen = false;
        long deadline = android.os.SystemClock.elapsedRealtime() + 20000;
        long startupDeadline = deadline;
        try {
            while (!closed) {
                LocalSocket current;
                try { current = connect(); }
                catch (IOException e) {
                    if (!absent(e)) throw e;
                    if (!autoStart && !seen) throw new IOException("尚未启动 Root 服务");
                    if (!launched && !seen) {
                        synchronized (this) {
                            if (closed) return;
                            launchAttempted = true;
                        }
                        launch(); launched = true;
                        startupDeadline = deadline = android.os.SystemClock.elapsedRealtime() + 20000;
                    }
                    if (android.os.SystemClock.elapsedRealtime() >= deadline)
                        throw new IOException("Root 服务不可用，请检查授权或 root-daemon.log");
                    Thread.sleep(250); continue;
                }
                synchronized (this) {
                    if (closed) { current.close(); return; }
                    socket = current;
                }
                try (LocalSocket connection = current) {
                    DataInputStream in = new DataInputStream(connection.getInputStream());
                    DataOutputStream out = new DataOutputStream(connection.getOutputStream());
                    while (!closed) {
                        Wire.writeText(out, "status"); out.flush();
                        JSONObject state = new JSONObject(Wire.readText(in));
                        if (state.optInt("protocol") != DaemonProtocol.VERSION || !version().equals(state.optString("version")))
                            throw new IllegalStateException("Root 服务版本不一致，请先停止再启动");
                        seen = true;
                        readySeen |= state.optBoolean("ready");
                        ready = readySeen;
                        if (!readySeen && android.os.SystemClock.elapsedRealtime() >= startupDeadline)
                            throw new IllegalStateException("Root 初始化超时，请停止服务并查看 root-daemon.log");
                        listener.state(state);
                        deadline = android.os.SystemClock.elapsedRealtime() + 7000;
                        Thread.sleep(2000);
                    }
                } catch (IOException e) {
                    // A stale socket after App freezing is replaced without touching the media session.
                    if (android.os.SystemClock.elapsedRealtime() >= deadline) throw new IOException("Root 管理连接无响应", e);
                    if (!closed) Thread.sleep(250);
                } finally { socket = null; }
                if (!readySeen && android.os.SystemClock.elapsedRealtime() >= startupDeadline)
                    throw new IOException("Root 初始化失败，请查看 root-daemon.log");
            }
        } catch (Exception e) {
            if (!closed) listener.disconnected(e.getMessage() == null ? "Root 管理连接失败" : e.getMessage());
        } finally { close(); }
    }
    static void stopDaemon(boolean waitForStart) throws Exception {
        LocalSocket connection;
        long startupDeadline = android.os.SystemClock.elapsedRealtime() + (waitForStart ? 20000 : 0);
        while (true) {
            try { connection = connect(); break; }
            catch (IOException e) {
                if (!absent(e)) throw e;
                if (android.os.SystemClock.elapsedRealtime() >= startupDeadline) return;
                Thread.sleep(100);
            }
        }
        try (LocalSocket current = connection) {
            DataOutputStream out = new DataOutputStream(current.getOutputStream());
            Wire.writeText(out, "stop"); out.flush();
            JSONObject reply = new JSONObject(Wire.readText(new DataInputStream(current.getInputStream())));
            if (!"stopping".equals(reply.optString("type"))) throw new IOException("Root 停止响应无效");
        }
        long deadline = android.os.SystemClock.elapsedRealtime() + 8000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            try (LocalSocket probe = connect()) { Thread.sleep(100); }
            catch (IOException e) { if (absent(e)) return; throw e; }
        }
        throw new IOException("Root 停止超时，请稍后重试");
    }
    @Override public synchronized void close() {
        closed = true;
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        reader.interrupt();
    }
}

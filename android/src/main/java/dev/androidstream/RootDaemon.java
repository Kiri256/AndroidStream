package dev.androidstream;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.PowerManager;
import dev.androidstream.root.RootCapture;
import org.json.JSONObject;
import java.io.*;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent root app_process. App management disconnects never stop media. */
public final class RootDaemon {
    private final int appUid;
    private final String version;
    private final LocalServerSocket manager;
    private final Set<LocalSocket> clients = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor management = new ThreadPoolExecutor(0, 2, 10, TimeUnit.SECONDS, new SynchronousQueue<>());
    private final AtomicBoolean stopping = new AtomicBoolean();
    private volatile RootCapture capture;
    private volatile RootSessionServer server;
    private PowerManager.WakeLock wake;

    private RootDaemon(int appUid, String version) throws IOException {
        this.appUid = appUid; this.version = version;
        // A second launch fails here before touching the first daemon's capture or listener.
        manager = new LocalServerSocket(DaemonProtocol.socketName(appUid));
    }
    public static void main(String[] args) {
        RootDaemon daemon = null;
        int result = 0;
        try {
            if (android.os.Process.myUid() != 0 || args.length != 4) throw new SecurityException("Invalid root launch");
            daemon = new RootDaemon(Integer.parseInt(args[0]), args[3]);
            daemon.run(args[1], args[2]);
        } catch (Throwable e) {
            result = 1;
            System.err.println("Root service failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (daemon != null) daemon.close();
            System.exit(result);
        }
    }
    private void run(String apk, String nativeLibrary) throws Exception {
        new Thread(this::accept, "root-manager").start();
        capture = new RootCapture();
        if (stopping.get()) return;
        android.content.Context context = capture.context();
        // Fail startup explicitly if the extracted library or headless JNI initialization fails.
        RtcSession.initializeRuntime(context, nativeLibrary);
        if (stopping.get()) return;
        wake = context.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndroidStream:root");
        wake.acquire(2 * 60 * 60 * 1000L);
        server = new RootSessionServer(context, capture, apk, nativeLibrary, this::stop);
        if (!stopping.get()) capture.runCommands();
    }
    private void accept() {
        while (!stopping.get()) {
            try {
                LocalSocket socket = manager.accept();
                if (socket.getPeerCredentials().getUid() != appUid) { socket.close(); continue; }
                socket.setSoTimeout(15000);
                clients.add(socket);
                if (stopping.get()) { socket.close(); clients.remove(socket); break; }
                try { management.execute(() -> manage(socket)); }
                catch (RejectedExecutionException e) { clients.remove(socket); socket.close(); }
            } catch (IOException e) { break; }
        }
    }
    private void manage(LocalSocket socket) {
        try {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            while (!stopping.get()) {
                String command = Wire.readText(in);
                DaemonProtocol.validateCommand(command);
                if (command.equals("stop")) {
                    Wire.writeText(out, "{\"type\":\"stopping\"}"); out.flush();
                    stop(); return;
                }
                RootSessionServer current = server;
                JSONObject state = current == null ? new JSONObject().put("type", "state").put("ready", false)
                        .put("status", "Root 正在初始化") : current.snapshot();
                state.put("protocol", DaemonProtocol.VERSION).put("version", version).put("pid", android.os.Process.myPid());
                Wire.writeText(out, state.toString()); out.flush();
            }
        } catch (Exception ignored) {
            // A frozen, killed or disconnected management App must not interrupt the browser.
        } finally { clients.remove(socket); try { socket.close(); } catch (IOException ignored) {} }
    }
    private void stop() {
        if (!stopping.compareAndSet(false, true)) return;
        Thread deadline = new Thread(() -> {
            try { Thread.sleep(6000); } catch (InterruptedException ignored) {}
            System.exit(0);
        }, "root-stop-deadline");
        deadline.setDaemon(true); deadline.start();
        RootCapture current = capture;
        if (current != null) current.requestStop();
    }
    private void close() {
        stop();
        try {
            if (server != null) server.close();
        } finally {
            try { if (capture != null) capture.close(); }
            finally {
                if (wake != null && wake.isHeld()) wake.release();
                try { manager.close(); } catch (IOException ignored) {}
                for (LocalSocket socket : clients) try { socket.close(); } catch (IOException ignored) {}
                management.shutdownNow();
            }
        }
    }
}

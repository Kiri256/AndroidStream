package dev.androidstream;

import android.app.*;
import android.content.*;
import android.os.*;
import org.json.JSONObject;

/** Optional foreground management UI; media and networking live in RootDaemon. */
public final class StreamService extends Service {
    static volatile StreamService instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private RootBridge root;
    private JSONObject state;
    private String status = "正在查找 Root 服务";
    private int generation;
    private boolean stopping, destroyed;

    @Override public void onCreate() {
        super.onCreate(); instance = this;
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("stream", "Root 服务管理", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, StreamService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        startForeground(1, new Notification.Builder(this, "stream").setContentTitle("AndroidStream Root 服务管理")
                .setContentText("关闭 App 不会停止共享；请使用停止按钮")
                .setSmallIcon(android.R.drawable.ic_menu_view).setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "停止", stop).build()).setOngoing(true).build());
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (stopping) return START_NOT_STICKY;
        String action = intent == null ? "attach" : intent.getAction();
        if ("stop".equals(action)) {
            stopping = true; status = "正在停止 Root 服务"; generation++;
            RootBridge previous = root;
            if (previous != null) { previous.close(); root = null; }
            boolean pendingStart = previous != null && previous.pendingStart();
            new Thread(() -> {
                try { RootBridge.stopDaemon(pendingStart); main.post(() -> { if (!destroyed) stopSelf(); }); }
                catch (Exception e) { main.post(() -> {
                    if (!destroyed) { stopping = false; status = "停止失败：" + e.getMessage(); }
                }); }
            }, "root-stop").start();
        } else if (root == null || !"attach".equals(action)) {
            if (root != null) root.close();
            boolean autoStart = !"attach".equals(action);
            int epoch = ++generation;
            status = autoStart ? "正在启动，请在 KernelSU 允许 Root" : "正在查找 Root 服务";
            root = new RootBridge(this, autoStart, new RootBridge.Listener() {
                public void state(JSONObject value) { main.post(() -> {
                    if (!destroyed && generation == epoch) { state = value; status = value.optString("status"); }
                }); }
                public void disconnected(String reason) { main.post(() -> {
                    if (destroyed || generation != epoch) return;
                    root = null; state = null; status = reason;
                    if (!autoStart && "尚未启动 Root 服务".equals(reason)) stopSelf();
                }); }
            });
            root.start();
        }
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    boolean canStart() { return root == null && !stopping; }
    String screenText() {
        JSONObject device = state == null ? null : state.optJSONObject("device");
        return "访问地址：\n" + (state == null ? "" : state.optString("address")) + "\n\n" + status
                + (device == null ? "" : "\n设备：" + device.optString("device") + " / Android SDK " + device.optInt("sdk")
                + "\n屏幕：" + device.optInt("width") + " × " + device.optInt("height") + "，Root UID " + device.optInt("uid"));
    }
    @Override public void onDestroy() {
        destroyed = true; generation++;
        if (instance == this) instance = null;
        if (root != null) root.close();
        main.removeCallbacksAndMessages(null);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}

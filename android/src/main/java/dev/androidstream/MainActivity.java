package dev.androidstream;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.*;
import android.net.Uri;
import android.provider.Settings;
import android.widget.*;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView state;
    private Button start;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            StreamService s = StreamService.instance;
            boolean unrestricted = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
            state.setText((s == null ? "尚未启动。连接 Wi-Fi 后启动，并在 KernelSU 中允许 AndroidStream 的 Root 请求。" : s.screenText())
                    + "\n\n后台运行：" + (unrestricted ? "已豁免系统电池优化" : "未豁免系统电池优化")
                    + "\n网络和媒体在独立 Root 进程运行。关闭 App 后仍会共享，请用停止按钮结束。");
            start.setEnabled(s == null || s.canStart());
            handler.postDelayed(this, 500);
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(36, 48, 36, 36);
        ScrollView scroll = new ScrollView(this); scroll.addView(box); setContentView(scroll);
        TextView title = new TextView(this); title.setText("AndroidStream · 实机测试版"); title.setTextSize(25); box.addView(title);
        TextView intro = new TextView(this); intro.setText("局域网控制 · Root · H.264 / Windows H.265\n音频为系统内部播放，不采集麦克风。\n"); box.addView(intro);
        state = new TextView(this); state.setTextSize(17); state.setTextIsSelectable(true); box.addView(state);
        start = button(box, "启动服务", () -> {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            startForegroundService(new Intent(this, StreamService.class));
        });
        button(box, "停止服务 / 断开全部连接", () -> startForegroundService(new Intent(this, StreamService.class).setAction("stop")));
        button(box, "后台运行设置", () -> {
            try {
                if (!getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName()))
                    startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
                else startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            } catch (android.content.ActivityNotFoundException e) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });
    }
    private Button button(LinearLayout parent, String label, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setOnClickListener(v -> action.run()); parent.addView(b); return b;
    }
    @Override public void onResume() {
        super.onResume();
        if (StreamService.instance == null) startForegroundService(new Intent(this, StreamService.class).setAction("attach"));
        handler.post(refresh);
    }
    @Override public void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
}

package dev.androidstream.root;

import android.graphics.Rect;
import android.hardware.display.VirtualDisplay;
import android.os.IBinder;
import android.os.Looper;
import android.view.Surface;
import java.lang.reflect.Method;

/** Hidden APIs are confined to this root-process backend. */
final class DisplayAccess {
    private final Object global;
    private Class<?> displayControl;
    private IBinder poweredToken;
    private android.os.PowerManager.WakeLock screenWake;
    DisplayAccess() throws Exception {
        if (Looper.myLooper() == null) Looper.prepareMainLooper();
        try {
            Class<?> vm = Class.forName("dalvik.system.VMRuntime");
            Object runtime = vm.getDeclaredMethod("getRuntime").invoke(null);
            vm.getDeclaredMethod("setHiddenApiExemptions", String[].class).invoke(runtime, (Object) new String[]{"L"});
        } catch (ReflectiveOperationException ignored) { /* app_process may already be exempt. */ }
        Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
        global = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null);
    }
    int[] geometry() throws Exception {
        Object info = global.getClass().getMethod("getDisplayInfo", int.class).invoke(global, 0);
        if (info == null) throw new IllegalStateException("Display 0 unavailable");
        Class<?> type = info.getClass();
        android.view.Display.Mode mode = (android.view.Display.Mode) type.getMethod("getMode").invoke(info);
        float refreshRate = dev.androidstream.VideoPolicy.refreshRate(mode.getRefreshRate());
        return new int[]{type.getField("logicalWidth").getInt(info), type.getField("logicalHeight").getInt(info),
                type.getField("rotation").getInt(info), type.getField("layerStack").getInt(info),
                Float.floatToIntBits(refreshRate), mode.getModeId()};
    }
    android.content.Context context() throws Exception {
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object current = thread.getMethod("currentActivityThread").invoke(null);
        android.content.Context system = (android.content.Context) thread.getMethod("getSystemContext").invoke(current);
        return new android.content.ContextWrapper(system) {
            @Override public android.content.Context getApplicationContext() { return this; }
        };
    }
    @SuppressWarnings("deprecation")
    synchronized void setScreenOff(boolean off) throws Exception {
        if (!off && poweredToken == null) return;
        Class<?> sc = Class.forName("android.view.SurfaceControl");
        Method power = sc.getMethod("setDisplayPowerMode", IBinder.class, int.class);
        if (off && poweredToken == null) {
            android.os.PowerManager manager = context().getSystemService(android.os.PowerManager.class);
            if (!manager.isInteractive()) throw new IllegalStateException("请先在手机上点亮并解锁屏幕");
            IBinder token;
            try { token = (IBinder) sc.getMethod("getInternalDisplayToken").invoke(null); }
            catch (NoSuchMethodException e) {
                Object info = global.getClass().getMethod("getDisplayInfo", int.class).invoke(global, 0);
                Object address = info.getClass().getField("address").get(info);
                long id = (long) address.getClass().getMethod("getPhysicalDisplayId").invoke(address);
                Class<?> owner = sc;
                try { owner.getMethod("getPhysicalDisplayToken", long.class); }
                catch (NoSuchMethodException removed) { owner = displayControl(); }
                token = (IBinder) owner.getMethod("getPhysicalDisplayToken", long.class).invoke(null, id);
            }
            if (token == null) throw new IllegalStateException("无法取得手机物理显示屏");
            screenWake = manager.newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "AndroidStream:screen");
            screenWake.acquire(7200000);
            poweredToken = token;
        }
        if (poweredToken != null) power.invoke(null, poweredToken, off ? 0 : 2);
        if (!off) {
            poweredToken = null;
            if (screenWake != null && screenWake.isHeld()) screenWake.release();
            screenWake = null;
        }
    }
    @android.annotation.SuppressLint({"BlockedPrivateApi", "PrivateApi"}) // Root app_process has hidden-API exemptions; never called by the App.
    private Class<?> displayControl() throws Exception {
        if (displayControl != null) return displayControl;
        // Android 14+ moved physical display discovery into system-server classes.
        Class<?> factory = Class.forName("com.android.internal.os.ClassLoaderFactory");
        Method create = factory.getDeclaredMethod("createClassLoader", String.class, String.class, String.class,
                ClassLoader.class, int.class, boolean.class, String.class);
        String path = android.system.Os.getenv("SYSTEMSERVERCLASSPATH");
        if (path == null || path.isEmpty()) path = "/system/framework/services.jar";
        ClassLoader loader = (ClassLoader) create.invoke(null, path, null, null, ClassLoader.getSystemClassLoader(), 0, true, null);
        Class<?> type = loader.loadClass("com.android.server.display.DisplayControl");
        Method load = Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
        load.setAccessible(true); load.invoke(Runtime.getRuntime(), type, "android_servers");
        displayControl = type;
        return type;
    }
    AutoCloseable mirror(Surface surface, int width, int height, int[] geometry) throws Exception {
        Exception modernFailure;
        try {
            // Static mirroring API used by recent Android versions, including Android 16.
            Method create = android.hardware.display.DisplayManager.class.getMethod("createVirtualDisplay",
                    String.class, int.class, int.class, int.class, Surface.class);
            VirtualDisplay display = (VirtualDisplay) create.invoke(null, "AndroidStream", width, height, 0, surface);
            if (display == null) throw new IllegalStateException("Null virtual display");
            return display::release;
        } catch (Exception e) { modernFailure = e; }
        try {
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            IBinder token = (IBinder) sc.getMethod("createDisplay", String.class, boolean.class).invoke(null, "AndroidStream", false);
            boolean success = false;
            try {
                sc.getMethod("openTransaction").invoke(null);
                try {
                    sc.getMethod("setDisplaySurface", IBinder.class, Surface.class).invoke(null, token, surface);
                    sc.getMethod("setDisplayProjection", IBinder.class, int.class, Rect.class, Rect.class)
                            .invoke(null, token, 0, new Rect(0, 0, geometry[0], geometry[1]), new Rect(0, 0, width, height));
                    sc.getMethod("setDisplayLayerStack", IBinder.class, int.class).invoke(null, token, geometry[3]);
                } finally { sc.getMethod("closeTransaction").invoke(null); }
                success = true;
                return () -> sc.getMethod("destroyDisplay", IBinder.class).invoke(null, token);
            } finally { if (!success) sc.getMethod("destroyDisplay", IBinder.class).invoke(null, token); }
        } catch (Exception e) {
            e.addSuppressed(modernFailure);
            throw new IllegalStateException("DisplayManager and SurfaceControl capture failed", e);
        }
    }
}

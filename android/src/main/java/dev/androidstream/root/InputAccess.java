package dev.androidstream.root;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import dev.androidstream.InputState;
import org.json.JSONObject;
import java.lang.reflect.Method;

final class InputAccess {
    private final Object manager;
    private final Method inject;
    private final InputState state = new InputState();
    private boolean pressed;
    private float x, y;
    private long downTime, lastActivity;
    private final java.util.Set<Integer> downKeys = new java.util.HashSet<>();
    InputAccess() throws Exception {
        Object instance;
        try {
            instance = Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            instance = Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null);
        }
        manager = instance;
        inject = manager.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);
    }
    private void send(InputEvent event) throws Exception {
        if (!Boolean.TRUE.equals(inject.invoke(manager, event, 0))) throw new IllegalStateException("Input injection rejected");
        if (event instanceof KeyEvent) {
            KeyEvent key = (KeyEvent) event;
            if (key.getAction() == KeyEvent.ACTION_DOWN) downKeys.add(key.getKeyCode());
            else if (key.getAction() == KeyEvent.ACTION_UP) downKeys.remove(key.getKeyCode());
        }
    }
    synchronized void command(JSONObject c, int[] geometry, int generation) throws Exception {
        lastActivity = SystemClock.uptimeMillis();
        String type = c.getString("type");
        if ("heartbeat".equals(type)) return;
        if ("cancelAll".equals(type)) { cancel(); return; }
        if ("touch".equals(type)) {
            float nextX = InputState.coordinate(c.getDouble("x"), geometry[0]);
            float nextY = InputState.coordinate(c.getDouble("y"), geometry[1]);
            String action = c.getString("action");
            if (!state.accept(action, c.getLong("gesture"), c.getLong("seq"), c.getInt("generation"), generation)) return;
            x = nextX; y = nextY;
            int a;
            switch (action) {
                case "down": a = MotionEvent.ACTION_DOWN; downTime = lastActivity; pressed = true; break;
                case "move": a = MotionEvent.ACTION_MOVE; break;
                case "up": a = MotionEvent.ACTION_UP; break;
                default: a = MotionEvent.ACTION_CANCEL;
            }
            motion(a);
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) pressed = false;
        } else if ("key".equals(type)) {
            int key = c.getInt("key");
            if (key != 3 && key != 4 && key != 187 && key != 24 && key != 25 && key != 66 && key != 67 && key != 61
                    && key != 19 && key != 20 && key != 21 && key != 22) throw new IllegalArgumentException("Key not allowed");
            long now = SystemClock.uptimeMillis();
            send(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
            send(new KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
        } else if ("text".equals(type)) {
            String text = c.getString("text");
            if (text.length() > 512) throw new IllegalArgumentException("Text too long");
            KeyEvent[] events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray());
            if (events == null) throw new IllegalArgumentException("This text requires an IME; initial build supports key-mappable text");
            for (KeyEvent event : events) send(event);
        }
    }
    private void motion(int action) throws Exception {
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y,
                action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL ? 0 : 1, 1, 0, 1, 1, 0, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { send(event); } finally { event.recycle(); }
    }
    synchronized void watchdog() { if ((pressed || !downKeys.isEmpty()) && SystemClock.uptimeMillis() - lastActivity > 2500) cancel(); }
    synchronized void cancel() {
        try { if (pressed) motion(MotionEvent.ACTION_CANCEL); } catch (Exception ignored) {}
        for (int key : new java.util.HashSet<>(downKeys)) {
            try { send(new KeyEvent(KeyEvent.ACTION_UP, key)); } catch (Exception ignored) {}
        }
        downKeys.clear();
        pressed = false;
        state.reset();
    }
}

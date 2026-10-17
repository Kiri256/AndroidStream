package dev.androidstream;

import org.json.JSONObject;

/** In-process boundary between WebRTC/session management and root capture/input. */
public interface CaptureBackend {
    interface Listener { void packet(int type, byte[] bytes); }
    void command(String text);
    default void command(JSONObject json) { command(json.toString()); }
}

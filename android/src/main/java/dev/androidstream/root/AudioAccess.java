package dev.androidstream.root;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.media.AudioFormat;
import android.media.AudioRecord;

/** Root-owned recorder with explicit shell attribution; no microphone fallback. */
@android.annotation.SuppressLint("PrivateApi") // Root-only, version-sensitive adapter.
final class AudioAccess {
    @android.annotation.SuppressLint({"WrongConstant", "MissingPermission"})
    static AudioRecord create() throws Exception {
        AudioRecord.Builder builder = new AudioRecord.Builder();
        if (android.os.Build.VERSION.SDK_INT >= 31)
            AudioRecord.Builder.class.getMethod("setContext", Context.class).invoke(builder, shellContext());
        int mask = AudioFormat.CHANNEL_IN_STEREO;
        int min = AudioRecord.getMinBufferSize(48000, mask, AudioFormat.ENCODING_PCM_16BIT);
        return builder.setAudioSource(8).setAudioFormat(new AudioFormat.Builder().setSampleRate(48000)
                .setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(Math.max(min * 2, 19200)).build();
    }
    @android.annotation.TargetApi(31)
    private static Context shellContext() throws Exception {
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object activityThread = thread.getMethod("currentActivityThread").invoke(null);
        Context system = (Context) thread.getMethod("getSystemContext").invoke(activityThread);
        return new ContextWrapper(system) {
            @Override public String getPackageName() { return "com.android.shell"; }
            @Override public String getOpPackageName() { return "com.android.shell"; }
            @Override public Context getApplicationContext() { return this; }
            @Override public AttributionSource getAttributionSource() {
                return new AttributionSource.Builder(2000).setPackageName("com.android.shell").build();
            }
        };
    }
}

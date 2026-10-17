package dev.androidstream;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Bounded management framing and internal media types. Never log payloads. */
public final class Wire {
    public static final int INFO = 1, VIDEO = 2, ERROR = 3, AUDIO = 5, SCREEN = 6;
    public static final int MAX_VIDEO = 8 * 1024 * 1024, MAX_COMMAND = 4096;
    private Wire() {}
    private static byte[] readBytes(DataInputStream in, int max) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > max) throw new IOException("Invalid IPC size");
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }
    private static void writeBytes(DataOutputStream out, byte[] bytes) throws IOException {
        out.writeInt(bytes.length);
        out.write(bytes);
    }
    public static void writeText(DataOutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_COMMAND) throw new IOException("Invalid IPC command size");
        writeBytes(out, bytes);
    }
    public static String readText(DataInputStream in) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(readBytes(in, MAX_COMMAND))).toString();
    }
}

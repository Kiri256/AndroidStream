package dev.androidstream;

import org.junit.Test;
import java.io.*;
import static org.junit.Assert.*;

public final class DaemonProtocolTest {
    @Test public void managementHasOnlyTwoExactCommands() throws Exception {
        DaemonProtocol.validateCommand("status");
        DaemonProtocol.validateCommand("stop");
        for (String value : new String[]{"", "stop\n", " status", "STATUS", "shell", "startAudio", "{\"type\":\"stop\"}", "stop\u0000status"}) {
            assertThrows(IOException.class, () -> DaemonProtocol.validateCommand(value));
        }
    }
    @Test public void endpointIsIsolatedByAppUid() {
        assertEquals("androidstream-manager-10234", DaemonProtocol.socketName(10234));
        assertNotEquals(DaemonProtocol.socketName(10234), DaemonProtocol.socketName(110234));
        assertThrows(IllegalArgumentException.class, () -> DaemonProtocol.socketName(0));
        assertThrows(IllegalArgumentException.class, () -> DaemonProtocol.socketName(-1));
    }
    @Test public void managementFramingRejectsOversizeAndTruncation() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        Wire.writeText(out, "status");
        Wire.writeText(out, "stop");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals("status", Wire.readText(in));
        assertEquals("stop", Wire.readText(in));
        assertEquals(-1, in.read());
        assertThrows(IOException.class, () -> Wire.writeText(out, "x".repeat(Wire.MAX_COMMAND + 1)));
        assertThrows(IOException.class, () -> Wire.readText(new DataInputStream(new ByteArrayInputStream(new byte[]{0, 0, 16, 1}))));
        assertThrows(EOFException.class, () -> Wire.readText(new DataInputStream(new ByteArrayInputStream(new byte[]{0, 0, 0, 4, 115}))));
    }
}

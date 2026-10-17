package dev.androidstream;

import org.junit.Test;
import java.io.*;
import static org.junit.Assert.*;

public class WireTest {
    @Test public void rejectsMalformedUtf8() {
        assertThrows(IOException.class, () -> Wire.readText(new DataInputStream(new ByteArrayInputStream(
                new byte[]{0, 0, 0, 2, (byte) 0xc3, 0x28}))));
    }
    @Test public void commandLimitAppliesToUtf8BytesBeforeWriting() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        assertThrows(IOException.class, () -> Wire.writeText(new DataOutputStream(buffer), "中".repeat(1500)));
        assertEquals(0, buffer.size());
    }
    @Test public void unicodeCommandsRoundTrip() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Wire.writeText(new DataOutputStream(buffer), "中文\n😀");
        assertEquals("中文\n😀", Wire.readText(new DataInputStream(new ByteArrayInputStream(buffer.toByteArray()))));
    }
    @Test public void rejectsNegativeOversizedAndTruncatedPackets() throws Exception {
        for (int size : new int[]{-1, Wire.MAX_COMMAND + 1}) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            new DataOutputStream(buffer).writeInt(size);
            assertThrows(IOException.class, () -> Wire.readText(new DataInputStream(new ByteArrayInputStream(buffer.toByteArray()))));
        }
        assertThrows(EOFException.class, () -> Wire.readText(new DataInputStream(new ByteArrayInputStream(new byte[]{0,0,0,2,1}))));
    }
}

package dev.androidstream;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public final class RootBridgeTest {
    @Test public void recognizesNativeLocalSocketErrorsWithoutErrnoNames() {
        for (String message : new String[]{"Connection refused", "connect failed: Connection refused",
                "No such file or directory", "connect failed: ENOENT (No such file or directory)",
                "connect failed: ECONNREFUSED (Connection refused)"}) {
            assertTrue(message, RootBridge.absent(new IOException(message)));
        }
    }
    @Test public void recognizesWrappedNativeErrors() {
        assertTrue(RootBridge.absent(new IOException("LocalSocket connect failed", new IOException("Connection refused"))));
    }
    @Test public void permissionTimeoutAndUnknownFailuresAreNotAbsentServices() {
        for (String message : new String[]{"Permission denied", "connect failed: EACCES (Permission denied)",
                "Operation not permitted", "Connection timed out", "Broken pipe", "Connection reset by peer", "", null}) {
            assertFalse(message, RootBridge.absent(new IOException(message)));
        }
    }
}

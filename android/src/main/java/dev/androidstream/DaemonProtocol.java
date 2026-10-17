package dev.androidstream;

import java.io.IOException;

/** UID-authenticated local administration only; never forwarded from the LAN. */
final class DaemonProtocol {
    static final int VERSION = 1;
    static String socketName(int appUid) {
        if (appUid <= 0) throw new IllegalArgumentException("Invalid app UID");
        return "androidstream-manager-" + appUid;
    }
    static void validateCommand(String command) throws IOException {
        if (!"status".equals(command) && !"stop".equals(command)) throw new IOException("Unsupported management command");
    }
}

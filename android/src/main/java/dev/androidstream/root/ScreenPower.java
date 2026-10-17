package dev.androidstream.root;

/** A controller lease prevents leaving the panel off after control/heartbeat loss. */
final class ScreenPower {
    interface Driver { void setOff(boolean off) throws Exception; }
    private final Driver driver;
    private boolean off;
    private long lastHeartbeat, switchedOff, nextRestore;
    ScreenPower(Driver driver) { this.driver = driver; }
    synchronized boolean isOff() { return off; }
    synchronized void heartbeat(long now) { lastHeartbeat = now; }
    synchronized void setOff(boolean value, long now) throws Exception {
        if (!value) { restore(); return; }
        // Arm cleanup before touching hardware: even a partial failure must be restored.
        off = true; lastHeartbeat = switchedOff = now;
        try { driver.setOff(true); }
        catch (Exception e) {
            try { restore(); } catch (Exception restoreError) { e.addSuppressed(restoreError); }
            throw e;
        }
    }
    synchronized void restore() throws Exception {
        if (!off) return;
        driver.setOff(false);
        off = false;
    }
    synchronized boolean check(long now) throws Exception {
        if (!off || now < nextRestore || now - lastHeartbeat < 5000 && now - switchedOff < 7200000) return false;
        nextRestore = now + 5000;
        restore(); return true;
    }
}

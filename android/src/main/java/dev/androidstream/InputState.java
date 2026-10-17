package dev.androidstream;

/** Single-pointer ordering independent of DataChannel delivery order. */
public final class InputState {
    private long active = -1, sequence = -1;
    public boolean accept(String action, long gesture, long seq, int generation, int currentGeneration) {
        if (generation != currentGeneration || gesture < 0 || seq < 0) return false;
        if ("down".equals(action)) {
            if (active != -1) return false;
            active = gesture;
            sequence = seq;
            return true;
        }
        if (gesture != active || active == -1 || seq <= sequence) return false;
        if (!"move".equals(action) && !"up".equals(action) && !"cancel".equals(action)) return false;
        sequence = seq;
        if ("up".equals(action) || "cancel".equals(action)) active = -1;
        return true;
    }
    public void reset() { active = -1; sequence = -1; }
    public static float coordinate(double normalized, int extent) {
        if (!Double.isFinite(normalized) || normalized < 0 || normalized > 1 || extent < 1)
            throw new IllegalArgumentException("Invalid coordinate");
        return (float) (normalized * (extent - 1));
    }
}

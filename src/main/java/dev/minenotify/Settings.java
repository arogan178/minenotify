package dev.minenotify;

public final class Settings {
    public enum DisplayMode { CHAT, POPUP, BOTH }
    public enum Position {
        TOP_LEFT(false, false), TOP_RIGHT(true, false), BOTTOM_LEFT(false, true), BOTTOM_RIGHT(true, true);
        public final boolean right;
        public final boolean bottom;
        Position(boolean right, boolean bottom) { this.right = right; this.bottom = bottom; }
        public String label() { return name().toLowerCase().replace('_', ' '); }
    }

    public DisplayMode displayMode = DisplayMode.POPUP;
    public Position position = Position.BOTTOM_RIGHT;
    public double scale = 1.0;
    public int durationSeconds = 8;
    public int offsetX = 12;
    public int offsetY = 40;
    public boolean enabled = true;
    public boolean sound = true;
    public boolean showPreview = false;

    public void normalize() {
        if (displayMode == null) displayMode = DisplayMode.POPUP;
        if (position == null) position = Position.BOTTOM_RIGHT;
        scale = Double.isFinite(scale) ? Math.clamp(scale, 0.75, 1.5) : 1.0;
        durationSeconds = Math.clamp(durationSeconds, 2, 30);
        offsetX = Math.clamp(offsetX, 0, 160);
        offsetY = Math.clamp(offsetY, 0, 160);
    }
}

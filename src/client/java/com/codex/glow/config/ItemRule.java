package com.codex.glow.config;

public final class ItemRule {
    public boolean enabled = false;
    public String color = HighlightConfig.DEFAULT_ITEM_COLOR;
    public int range = HighlightConfig.DEFAULT_RANGE;
    public boolean throughWalls = true;

    public void sanitize() {
        color = HighlightConfig.sanitizeColor(color, HighlightConfig.DEFAULT_ITEM_COLOR);
        range = HighlightConfig.clamp(range, 1, 512);
    }
}

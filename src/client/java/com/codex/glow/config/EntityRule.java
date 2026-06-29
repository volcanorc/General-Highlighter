package com.codex.glow.config;

public final class EntityRule {
    public boolean enabled = false;
    public String color = HighlightConfig.DEFAULT_ENTITY_COLOR;
    public int range = HighlightConfig.DEFAULT_RANGE;
    public boolean throughWalls = true;

    public void sanitize() {
        color = HighlightConfig.sanitizeColor(color, HighlightConfig.DEFAULT_ENTITY_COLOR);
        range = HighlightConfig.clamp(range, 1, 512);
    }
}

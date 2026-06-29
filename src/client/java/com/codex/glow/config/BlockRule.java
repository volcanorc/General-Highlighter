package com.codex.glow.config;

public final class BlockRule {
    public boolean enabled = false;
    public String color = HighlightConfig.DEFAULT_BLOCK_COLOR;
    public int range = HighlightConfig.DEFAULT_RANGE;
    public BlockRenderMode mode = BlockRenderMode.BOX;
    public boolean throughWalls = true;
    public int maxHighlights = 2048;

    public void sanitize() {
        color = HighlightConfig.sanitizeColor(color, HighlightConfig.DEFAULT_BLOCK_COLOR);
        range = HighlightConfig.clamp(range, 1, 512);
        if (mode == null) {
            mode = BlockRenderMode.BOX;
        }
        maxHighlights = HighlightConfig.clamp(maxHighlights, 1, 20000);
    }
}

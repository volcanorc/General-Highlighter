package com.codex.glow.config;

public final class BlockRule {
    public boolean enabled = false;
    public String color = HighlightConfig.DEFAULT_BLOCK_COLOR;
    public int range = HighlightConfig.DEFAULT_RANGE;
    public BlockRenderMode mode = BlockRenderMode.OVERLAY;
    public boolean throughWalls = true;
    public int maxHighlights = 2048;
    public int maxClusters = 512;
    public int fillAlpha = 128;

    public void sanitize() {
        color = HighlightConfig.sanitizeColor(color, HighlightConfig.DEFAULT_BLOCK_COLOR);
        range = HighlightConfig.clamp(range, 1, 512);
        if (mode == null) {
            mode = BlockRenderMode.OVERLAY;
        }
        maxHighlights = HighlightConfig.clamp(maxHighlights, 1, 20000);
        maxClusters = HighlightConfig.clamp(maxClusters, 1, 5000);
        fillAlpha = HighlightConfig.clamp(fillAlpha, 16, 220);
    }
}

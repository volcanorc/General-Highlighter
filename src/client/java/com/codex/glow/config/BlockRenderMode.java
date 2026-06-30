package com.codex.glow.config;

public enum BlockRenderMode {
    OVERLAY,
    BOX,
    CLUSTER,
    FILLED,
    FILLED_CLUSTER;

    public BlockRenderMode next() {
        return switch (this) {
            case OVERLAY -> BOX;
            case BOX -> CLUSTER;
            case CLUSTER -> FILLED;
            case FILLED -> FILLED_CLUSTER;
            case FILLED_CLUSTER -> OVERLAY;
        };
    }

    public String displayName() {
        return switch (this) {
            case OVERLAY -> "Green overlay";
            case BOX -> "Outline";
            case CLUSTER -> "Smart cluster";
            case FILLED -> "Filled glow";
            case FILLED_CLUSTER -> "Filled cluster";
        };
    }
}

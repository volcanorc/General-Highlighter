package com.codex.glow.config;

public enum BlockRenderMode {
    BOX,
    CLUSTER,
    FILLED,
    FILLED_CLUSTER;

    public BlockRenderMode next() {
        return switch (this) {
            case BOX -> CLUSTER;
            case CLUSTER -> FILLED;
            case FILLED -> FILLED_CLUSTER;
            case FILLED_CLUSTER -> BOX;
        };
    }

    public String displayName() {
        return switch (this) {
            case BOX -> "Outline";
            case CLUSTER -> "Smart cluster";
            case FILLED -> "Filled glow";
            case FILLED_CLUSTER -> "Filled cluster";
        };
    }
}

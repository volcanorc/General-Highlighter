package com.codex.glow.config;

public enum BlockRenderMode {
    BOX,
    CLUSTER;

    public BlockRenderMode next() {
        return this == BOX ? CLUSTER : BOX;
    }
}

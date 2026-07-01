package com.codex.glow.config;

import java.util.LinkedHashSet;
import java.util.Set;

public final class SmartToolRule {
    public boolean registered = false;
    public boolean enabled = true;
    public String label = "Mob highlight";
    public String matchType = "ITEM_ID";
    public String itemId = "";
    public String customNameJson = "";
    public String displayName = "";
    public int durationSeconds = 10;
    public int range = HighlightConfig.DEFAULT_RANGE;
    public String color = HighlightConfig.DEFAULT_ENTITY_COLOR;
    public String blockColor = HighlightConfig.DEFAULT_BLOCK_COLOR;
    public boolean throughWalls = true;
    public boolean allMobs = true;
    public boolean allItems = true;
    public Set<String> entityTargets = new LinkedHashSet<>();
    public Set<String> itemTargets = new LinkedHashSet<>();
    public Set<String> blockTargets = new LinkedHashSet<>();

    public void sanitize() {
        if (!"CUSTOM_NAME".equals(matchType)) {
            matchType = "ITEM_ID";
        }
        if (itemId == null) {
            itemId = "";
        }
        if (customNameJson == null) {
            customNameJson = "";
        }
        if (displayName == null || displayName.isBlank()) {
            displayName = itemId.isBlank() ? "Unregistered" : itemId;
        }
        if (label == null || label.isBlank()) {
            label = displayName;
        }
        durationSeconds = HighlightConfig.clamp(durationSeconds, 1, 120);
        range = HighlightConfig.clamp(range, 1, 512);
        color = HighlightConfig.sanitizeColor(color, HighlightConfig.DEFAULT_ENTITY_COLOR);
        blockColor = HighlightConfig.sanitizeColor(blockColor, HighlightConfig.DEFAULT_BLOCK_COLOR);
        if (entityTargets == null) {
            entityTargets = new LinkedHashSet<>();
        }
        if (itemTargets == null) {
            itemTargets = new LinkedHashSet<>();
        }
        if (blockTargets == null) {
            blockTargets = new LinkedHashSet<>();
        }
    }

    public BlockRule asBlockRule() {
        BlockRule rule = new BlockRule();
        rule.enabled = true;
        rule.color = blockColor;
        rule.range = range;
        rule.throughWalls = throughWalls;
        rule.fillAlpha = 128;
        rule.sanitize();
        return rule;
    }
}

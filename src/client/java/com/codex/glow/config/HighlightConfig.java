package com.codex.glow.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

public final class HighlightConfig {
    public static final String DEFAULT_ENTITY_COLOR = "#FFFFFF";
    public static final String DEFAULT_BLOCK_COLOR = "#00FF55";
    public static final String DEFAULT_ITEM_COLOR = "#FFFF00";
    public static final int DEFAULT_RANGE = 128;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");

    public Map<String, EntityRule> entities = new LinkedHashMap<>();
    public Map<String, BlockRule> blocks = new LinkedHashMap<>();
    public Map<String, ItemRule> items = new LinkedHashMap<>();
    public boolean showNoisyBlocks = false;
    public boolean useLoadedChunkRange = false;

    private transient Path path;
    private transient boolean dirty;

    public static HighlightConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("general-highlighter.json");
        HighlightConfig config = null;

        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                config = GSON.fromJson(reader, HighlightConfig.class);
            } catch (Exception ignored) {
                config = null;
            }
        }

        if (config == null) {
            config = new HighlightConfig();
            config.dirty = true;
        }

        config.path = path;
        config.sanitize();
        config.ensureStarterRules();
        config.saveIfDirty();
        return config;
    }

    private void sanitize() {
        if (entities == null) {
            entities = new LinkedHashMap<>();
            dirty = true;
        }
        if (blocks == null) {
            blocks = new LinkedHashMap<>();
            dirty = true;
        }
        if (items == null) {
            items = new LinkedHashMap<>();
            dirty = true;
        }

        entities.values().forEach(EntityRule::sanitize);
        blocks.values().forEach(BlockRule::sanitize);
        items.values().forEach(ItemRule::sanitize);
    }

    private void ensureStarterRules() {
        ensureEntityRule("minecraft:zombie");
        ensureAllDroppedItemsRule();
        ensureBlockRule("minecraft:chest");
        ensureItemRule("minecraft:diamond");
    }

    private void ensureAllDroppedItemsRule() {
        EntityRule rule = entities.get("minecraft:item");
        if (rule == null) {
            rule = new EntityRule();
            rule.color = DEFAULT_ITEM_COLOR;
            entities.put("minecraft:item", rule);
            dirty = true;
        }
        rule.sanitize();
    }

    public EntityRule ensureEntityRule(Identifier id) {
        return ensureEntityRule(id.toString());
    }

    public EntityRule ensureEntityRule(String id) {
        EntityRule rule = entities.get(id);
        if (rule == null) {
            rule = new EntityRule();
            entities.put(id, rule);
            dirty = true;
        }
        rule.sanitize();
        return rule;
    }

    public BlockRule ensureBlockRule(Identifier id) {
        return ensureBlockRule(id.toString());
    }

    public BlockRule ensureBlockRule(String id) {
        BlockRule rule = blocks.get(id);
        if (rule == null) {
            rule = new BlockRule();
            blocks.put(id, rule);
            dirty = true;
        }
        rule.sanitize();
        return rule;
    }

    public ItemRule ensureItemRule(Identifier id) {
        return ensureItemRule(id.toString());
    }

    public ItemRule ensureItemRule(String id) {
        ItemRule rule = items.get(id);
        if (rule == null) {
            rule = new ItemRule();
            items.put(id, rule);
            dirty = true;
        }
        rule.sanitize();
        return rule;
    }

    public boolean shouldHighlightEntity(Identifier id, Entity entity) {
        if (entity instanceof ItemEntity itemEntity && shouldHighlightItem(itemEntity)) {
            return true;
        }

        EntityRule rule = entities.get(id.toString());
        if (rule == null || !rule.enabled) {
            return false;
        }
        if (!MinecraftClientAccess.playerWithinRange(entity, rule.range)) {
            return false;
        }
        if (!rule.throughWalls && !MinecraftClientAccess.playerCanSee(entity)) {
            return false;
        }
        return true;
    }

    public Integer getEntityColor(Identifier id, Entity entity) {
        if (entity instanceof ItemEntity itemEntity) {
            ItemRule itemRule = getEnabledItemRule(itemEntity);
            if (itemRule != null && passesEntityChecks(itemEntity, itemRule.range, itemRule.throughWalls)) {
                return parseColor(itemRule.color);
            }
        }

        EntityRule rule = entities.get(id.toString());
        if (rule == null || !rule.enabled || !shouldHighlightEntity(id, entity)) {
            return null;
        }
        return parseColor(rule.color);
    }

    public boolean hasEnabledBlocks() {
        return blocks.values().stream().anyMatch(rule -> rule.enabled);
    }

    public int maxEnabledBlockRange() {
        return blocks.values().stream()
                .filter(rule -> rule.enabled)
                .mapToInt(rule -> rule.range)
                .max()
                .orElse(DEFAULT_RANGE);
    }

    public int maxBlockHighlights() {
        return blocks.values().stream()
                .filter(rule -> rule.enabled)
                .mapToInt(rule -> rule.maxHighlights)
                .max()
                .orElse(2048);
    }

    public BlockRule getEnabledBlockRule(Identifier id) {
        BlockRule rule = blocks.get(id.toString());
        if (rule == null || !rule.enabled) {
            return null;
        }
        return rule;
    }

    public ItemRule getEnabledItemRule(ItemEntity entity) {
        Identifier itemId = Registries.ITEM.getId(entity.getStack().getItem());
        ItemRule rule = items.get(itemId.toString());
        if (rule == null || !rule.enabled) {
            return null;
        }
        return rule;
    }

    public boolean shouldHighlightItem(ItemEntity entity) {
        ItemRule rule = getEnabledItemRule(entity);
        return rule != null && passesEntityChecks(entity, rule.range, rule.throughWalls);
    }

    private boolean passesEntityChecks(Entity entity, int range, boolean throughWalls) {
        if (!MinecraftClientAccess.playerWithinRange(entity, range)) {
            return false;
        }
        return throughWalls || MinecraftClientAccess.playerCanSee(entity);
    }

    public void markDirty() {
        dirty = true;
    }

    public void saveIfDirty() {
        if (dirty) {
            save();
        }
    }

    public void save() {
        if (path == null) {
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(this, writer);
            }
            dirty = false;
        } catch (IOException ignored) {
        }
    }

    public static int parseColor(String color) {
        String safe = sanitizeColor(color, DEFAULT_ENTITY_COLOR).substring(1);
        return Integer.parseInt(safe, 16);
    }

    public static String sanitizeColor(String color, String fallback) {
        if (color != null && HEX_COLOR.matcher(color).matches()) {
            return color.toUpperCase();
        }
        return fallback;
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static String displayName(Identifier id) {
        String path = id.getPath().replace('_', ' ');
        if (path.isEmpty()) {
            return id.toString();
        }
        return Character.toUpperCase(path.charAt(0)) + path.substring(1);
    }

    private static final class MinecraftClientAccess {
        private static boolean playerWithinRange(Entity entity, int range) {
            net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
            if (client.player == null) {
                return false;
            }
            return client.player.squaredDistanceTo(entity) <= (double) range * range;
        }

        private static boolean playerCanSee(Entity entity) {
            net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
            if (client.player == null) {
                return false;
            }
            return client.player.canSee(entity);
        }
    }
}

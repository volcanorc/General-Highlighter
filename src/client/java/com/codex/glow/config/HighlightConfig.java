package com.codex.glow.config;

import com.codex.glow.mixin.client.ItemRendererAccessor;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.color.item.ItemColors;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
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
    public ItemRule allDroppedItems = new ItemRule();
    public boolean showNoisyBlocks = false;
    public boolean useLoadedChunkRange = false;
    public Boolean fastScanOnToggle = true;

    private transient Path path;
    private transient boolean dirty;
    private transient Map<Block, BlockRule> enabledBlockRules = new IdentityHashMap<>();
    private transient boolean blockRuleCacheDirty = true;

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
        if (allDroppedItems == null) {
            allDroppedItems = new ItemRule();
            dirty = true;
        }
        if (fastScanOnToggle == null) {
            fastScanOnToggle = true;
            dirty = true;
        }
        enabledBlockRules = new IdentityHashMap<>();
        blockRuleCacheDirty = true;

        entities.values().forEach(EntityRule::sanitize);
        blocks.values().forEach(BlockRule::sanitize);
        items.values().forEach(ItemRule::sanitize);
        allDroppedItems.sanitize();
    }

    private void ensureStarterRules() {
        ensureEntityRule("minecraft:zombie");
        ensureEntityRule("minecraft:item");
        ensureBlockRule("minecraft:chest");
        ensureItemRule("minecraft:diamond");
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
        if (entity instanceof ItemEntity itemEntity) {
            return shouldHighlightItem(itemEntity);
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
                return itemRule.autoColor ? getAutomaticItemColor(itemEntity.getStack()) : parseColor(itemRule.color);
            }
            return null;
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

    public BlockRule getEnabledBlockRule(Block block) {
        if (blockRuleCacheDirty) {
            rebuildEnabledBlockRuleCache();
        }
        return enabledBlockRules.get(block);
    }

    public ItemRule getEnabledItemRule(ItemEntity entity) {
        Identifier itemId = Registries.ITEM.getId(entity.getStack().getItem());
        ItemRule rule = items.get(itemId.toString());
        if (rule != null && rule.enabled) {
            return rule;
        }
        return allDroppedItems.enabled ? allDroppedItems : null;
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
        blockRuleCacheDirty = true;
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

    private void rebuildEnabledBlockRuleCache() {
        enabledBlockRules.clear();
        for (Map.Entry<String, BlockRule> entry : blocks.entrySet()) {
            BlockRule rule = entry.getValue();
            if (rule == null || !rule.enabled) {
                continue;
            }
            Identifier id = Identifier.tryParse(entry.getKey());
            if (id != null && Registries.BLOCK.containsId(id)) {
                enabledBlockRules.put(Registries.BLOCK.get(id), rule);
            }
        }
        blockRuleCacheDirty = false;
    }

    private static int getAutomaticItemColor(ItemStack stack) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.getItemRenderer() instanceof ItemRendererAccessor accessor) {
            ItemColors colors = accessor.generalHighlighter$getColors();
            int tint = colors.getColor(stack, 0);
            if (tint != -1) {
                return tint & 0xFFFFFF;
            }
        }

        Identifier id = Registries.ITEM.getId(stack.getItem());
        String path = id.getPath();
        if (path.contains("diamond")) {
            return 0x55DDFF;
        }
        if (path.contains("redstone")) {
            return 0xFF2A2A;
        }
        if (path.contains("emerald")) {
            return 0x24D45A;
        }
        if (path.contains("gold")) {
            return 0xFFD84D;
        }
        if (path.contains("iron") || path.contains("quartz")) {
            return 0xDDE4EA;
        }
        if (path.contains("sugar_cane") || path.contains("kelp") || path.contains("bamboo")
                || path.contains("sapling") || path.contains("leaves") || path.contains("seeds")
                || path.contains("wheat") || path.contains("carrot") || path.contains("potato")
                || path.contains("cactus")) {
            return 0x55CC55;
        }
        return parseColor(DEFAULT_ITEM_COLOR);
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

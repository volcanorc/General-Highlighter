package com.codex.glow.smart;

import com.codex.glow.config.BlockRule;
import com.codex.glow.config.HighlightConfig;
import com.codex.glow.config.SmartToolRule;
import com.codex.glow.highlight.BlockScanner;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import java.util.Map;

public final class SmartHighlightManager {
    public static final String MOB_TOOL_ID = "mob";

    private final HighlightConfig config;
    private BlockScanner scanner;
    private long tick;
    private long activeUntilTick;
    private String activeToolId = "";

    public SmartHighlightManager(HighlightConfig config) {
        this.config = config;
        this.config.ensureSmartTool(MOB_TOOL_ID);
    }

    public void setScanner(BlockScanner scanner) {
        this.scanner = scanner;
    }

    public void tick(MinecraftClient client) {
        tick++;
        if (activeUntilTick > 0 && tick >= activeUntilTick) {
            activeToolId = "";
            activeUntilTick = 0;
            if (scanner != null) {
                scanner.requestRescan();
            }
        }
    }

    public boolean handleUse(PlayerEntity player, World world, Hand hand) {
        if (!world.isClient || player == null) {
            return false;
        }
        ItemStack stack = player.getStackInHand(hand);
        if (stack.isEmpty()) {
            return false;
        }

        for (Map.Entry<String, SmartToolRule> entry : config.smartTools.entrySet()) {
            SmartToolRule rule = entry.getValue();
            if (rule != null && rule.enabled && rule.registered && matches(rule, stack)) {
                activate(entry.getKey(), rule);
                return true;
            }
        }
        return false;
    }

    public void registerMainhand(PlayerEntity player, String id, boolean defaultTargets) {
        if (player == null) {
            return;
        }
        ItemStack stack = player.getMainHandStack();
        if (stack.isEmpty()) {
            return;
        }

        SmartToolRule rule = config.ensureSmartTool(id);
        applyMatcher(rule, stack);
        rule.registered = true;
        rule.enabled = true;
        if (defaultTargets) {
            rule.allMobs = true;
            rule.allItems = true;
            rule.blockTargets.clear();
        } else {
            rule.allMobs = false;
            rule.allItems = false;
            rule.entityTargets.clear();
            rule.itemTargets.clear();
            rule.blockTargets.clear();
        }
        config.markDirty();
    }

    public String createCustomTool(PlayerEntity player) {
        String id = "custom-" + System.currentTimeMillis();
        SmartToolRule rule = config.ensureSmartTool(id);
        rule.label = "Custom tool";
        registerMainhand(player, id, false);
        return id;
    }

    public boolean shouldHighlightEntity(Entity entity) {
        SmartToolRule rule = activeRule();
        if (rule == null || entity == null) {
            return false;
        }
        if (!withinRange(entity, rule.range)) {
            return false;
        }
        if (!rule.throughWalls && !canSee(entity)) {
            return false;
        }
        if (entity instanceof ItemEntity itemEntity) {
            Identifier itemId = Registries.ITEM.getId(itemEntity.getStack().getItem());
            return rule.allItems || rule.itemTargets.contains(itemId.toString());
        }
        Identifier entityId = Registries.ENTITY_TYPE.getId(entity.getType());
        return (rule.allMobs && entity instanceof LivingEntity && !(entity instanceof PlayerEntity))
                || rule.entityTargets.contains(entityId.toString());
    }

    public Integer getEntityColor(Entity entity) {
        SmartToolRule rule = activeRule();
        if (rule == null || !shouldHighlightEntity(entity)) {
            return null;
        }
        if (entity instanceof ItemEntity itemEntity) {
            return HighlightConfig.getAutomaticItemColor(itemEntity.getStack());
        }
        return HighlightConfig.parseColor(rule.color);
    }

    public boolean hasActiveBlockTargets() {
        SmartToolRule rule = activeRule();
        return rule != null && !rule.blockTargets.isEmpty();
    }

    public int activeBlockRange() {
        SmartToolRule rule = activeRule();
        return rule == null ? 0 : rule.range;
    }

    public BlockRule getEnabledBlockRule(Block block) {
        SmartToolRule rule = activeRule();
        if (rule == null) {
            return null;
        }
        Identifier id = Registries.BLOCK.getId(block);
        return rule.blockTargets.contains(id.toString()) ? rule.asBlockRule() : null;
    }

    public long remainingSeconds() {
        if (activeUntilTick <= tick) {
            return 0;
        }
        return Math.max(1, (activeUntilTick - tick + 19) / 20);
    }

    public String activeToolLabel() {
        SmartToolRule rule = activeRule();
        return rule == null ? "" : rule.label;
    }

    private void activate(String id, SmartToolRule rule) {
        activeToolId = id;
        activeUntilTick = tick + (long) rule.durationSeconds * 20L;
        if (scanner != null && !rule.blockTargets.isEmpty()) {
            scanner.requestPriorityRescan();
        }
    }

    private SmartToolRule activeRule() {
        if (activeToolId == null || activeToolId.isBlank() || activeUntilTick <= tick) {
            return null;
        }
        SmartToolRule rule = config.smartTools.get(activeToolId);
        if (rule == null || !rule.enabled || !rule.registered) {
            return null;
        }
        return rule;
    }

    private static boolean matches(SmartToolRule rule, ItemStack stack) {
        if ("CUSTOM_NAME".equals(rule.matchType)) {
            Text customName = stack.get(DataComponentTypes.CUSTOM_NAME);
            return customName != null && rule.customNameJson.equals(customNameJson(customName));
        }
        Identifier itemId = Registries.ITEM.getId(stack.getItem());
        return rule.itemId.equals(itemId.toString());
    }

    private static void applyMatcher(SmartToolRule rule, ItemStack stack) {
        Identifier itemId = Registries.ITEM.getId(stack.getItem());
        rule.itemId = itemId.toString();
        Text customName = stack.get(DataComponentTypes.CUSTOM_NAME);
        if (customName != null) {
            rule.matchType = "CUSTOM_NAME";
            rule.customNameJson = customNameJson(customName);
            rule.displayName = customName.getString();
            rule.label = customName.getString();
        } else {
            rule.matchType = "ITEM_ID";
            rule.customNameJson = "";
            rule.displayName = HighlightConfig.displayName(itemId);
            rule.label = HighlightConfig.displayName(itemId);
        }
        rule.sanitize();
    }

    private static String customNameJson(Text text) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            return text.getString();
        }
        return Text.Serialization.toJsonString(text, client.world.getRegistryManager());
    }

    private static boolean withinRange(Entity entity, int range) {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.player != null && client.player.squaredDistanceTo(entity) <= (double) range * range;
    }

    private static boolean canSee(Entity entity) {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.player != null && client.player.canSee(entity);
    }
}

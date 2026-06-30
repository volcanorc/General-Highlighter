package com.codex.glow.screen;

import com.codex.glow.config.HighlightConfig;
import com.codex.glow.config.SmartToolRule;
import com.codex.glow.highlight.BlockScanner;
import com.codex.glow.smart.SmartHighlightManager;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class SmartHighlighterScreen extends Screen {
    private static final int ROW_HEIGHT = 18;
    private static final int LIST_TOP = 76;
    private static String selectedToolId = SmartHighlightManager.MOB_TOOL_ID;
    private static int scroll;

    private final HighlightConfig config;
    private final BlockScanner scanner;
    private final SmartHighlightManager smartManager;
    private TextFieldWidget durationField;
    private ButtonWidget registerButton;
    private ButtonWidget customButton;
    private ButtonWidget enabledButton;
    private ButtonWidget mobsButton;
    private ButtonWidget itemsButton;
    private ButtonWidget blocksButton;
    private ButtonWidget allMobsButton;
    private ButtonWidget allItemsButton;
    private boolean syncing;

    public SmartHighlighterScreen(HighlightConfig config, BlockScanner scanner, SmartHighlightManager smartManager) {
        super(Text.literal("Smart Highlighter"));
        this.config = config;
        this.scanner = scanner;
        this.smartManager = smartManager;
        this.config.ensureSmartTool(SmartHighlightManager.MOB_TOOL_ID);
    }

    @Override
    protected void init() {
        addDrawableChild(ButtonWidget.builder(Text.literal("Entities"), button ->
                client.setScreen(new HighlighterScreen(Text.literal("Highlighter"), config, scanner, "entities")))
                .dimensions(12, 26, 76, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Blocks"), button ->
                client.setScreen(new HighlighterScreen(Text.literal("Highlighter"), config, scanner, "blocks")))
                .dimensions(94, 26, 76, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Items"), button ->
                client.setScreen(new HighlighterScreen(Text.literal("Highlighter"), config, scanner, "items")))
                .dimensions(176, 26, 76, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Smart"), button -> refresh())
                .dimensions(258, 26, 76, 20).build());

        int sideX = width - 172;
        registerButton = addDrawableChild(ButtonWidget.builder(Text.literal("Register mainhand"), button -> {
            if (client != null && client.player != null) {
                smartManager.registerMainhand(client.player, selectedToolId, true);
                config.markDirty();
                refresh();
            }
        }).dimensions(sideX, 58, 160, 20).build());

        customButton = addDrawableChild(ButtonWidget.builder(Text.literal("Create custom tool"), button -> {
            if (client != null && client.player != null) {
                selectedToolId = smartManager.createCustomTool(client.player);
                config.markDirty();
                refresh();
            }
        }).dimensions(sideX, 84, 160, 20).build());

        enabledButton = addDrawableChild(ButtonWidget.builder(Text.literal("Enabled"), button -> {
            SmartToolRule rule = selectedRule();
            rule.enabled = !rule.enabled;
            config.markDirty();
            refresh();
        }).dimensions(sideX, 110, 160, 20).build());

        durationField = new TextFieldWidget(textRenderer, sideX, 154, 160, 18, Text.literal("Duration"));
        durationField.setMaxLength(3);
        durationField.setChangedListener(value -> {
            if (syncing) {
                return;
            }
            try {
                selectedRule().durationSeconds = HighlightConfig.clamp(Integer.parseInt(value), 1, 120);
                config.markDirty();
            } catch (NumberFormatException ignored) {
            }
        });
        addDrawableChild(durationField);

        allMobsButton = addDrawableChild(ButtonWidget.builder(Text.literal("All mobs"), button -> {
            SmartToolRule rule = selectedRule();
            rule.allMobs = !rule.allMobs;
            if (rule.allMobs) {
                rule.entityTargets.clear();
            }
            config.markDirty();
            refresh();
        }).dimensions(sideX, 184, 160, 20).build());

        allItemsButton = addDrawableChild(ButtonWidget.builder(Text.literal("All items"), button -> {
            SmartToolRule rule = selectedRule();
            rule.allItems = !rule.allItems;
            if (rule.allItems) {
                rule.itemTargets.clear();
            }
            config.markDirty();
            refresh();
        }).dimensions(sideX, 210, 160, 20).build());

        mobsButton = addDrawableChild(ButtonWidget.builder(Text.literal("Show mobs highlighted"), button ->
                client.setScreen(new SmartTargetScreen(this, config, selectedToolId, SmartTargetScreen.TargetType.ENTITIES)))
                .dimensions(sideX, 248, 160, 20).build());
        itemsButton = addDrawableChild(ButtonWidget.builder(Text.literal("Show items highlighted"), button ->
                client.setScreen(new SmartTargetScreen(this, config, selectedToolId, SmartTargetScreen.TargetType.ITEMS)))
                .dimensions(sideX, 274, 160, 20).build());
        blocksButton = addDrawableChild(ButtonWidget.builder(Text.literal("Show blocks highlighted"), button ->
                client.setScreen(new SmartTargetScreen(this, config, selectedToolId, SmartTargetScreen.TargetType.BLOCKS)))
                .dimensions(sideX, 300, 160, 20).build());

        refresh();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        context.drawTextWithShadow(textRenderer, title, 12, 10, 0xFFFFFF);

        List<String> tools = visibleTools();
        int listWidth = Math.max(120, width - 204);
        int listBottom = height - 18;
        int rows = Math.max(1, (listBottom - LIST_TOP) / ROW_HEIGHT);
        scroll = HighlightConfig.clamp(scroll, 0, Math.max(0, tools.size() - rows));
        for (int i = 0; i < Math.min(rows, tools.size() - scroll); i++) {
            String id = tools.get(scroll + i);
            SmartToolRule rule = config.ensureSmartTool(id);
            int y = LIST_TOP + i * ROW_HEIGHT;
            boolean selected = id.equals(selectedToolId);
            context.fill(12, y, 12 + listWidth, y + ROW_HEIGHT - 2, selected ? 0x703A7BFF : 0x40111111);
            context.fill(16, y + 4, 26, y + 14, rule.registered ? 0xFF4DFF7D : 0xFFFF4444);
            context.drawTextWithShadow(textRenderer, rule.label, 32, y + 5, rule.registered ? 0xFFFFFF : 0xFFBBBB);
            context.drawTextWithShadow(textRenderer, rule.displayName, Math.min(width - 350, 168), y + 5, 0x888888);
        }

        int sideX = width - 172;
        SmartToolRule rule = selectedRule();
        context.drawTextWithShadow(textRenderer, rule.registered ? "Registered" : "Unregistered", sideX, 136,
                rule.registered ? 0x55FF77 : 0xFF5555);
        context.drawTextWithShadow(textRenderer, "Duration seconds", sideX, 144, 0xCFCFCF);
        context.drawTextWithShadow(textRenderer, "Active: " + activeStatus(), sideX, 330, 0x9BE7FF);
        context.drawTextWithShadow(textRenderer, "Register uses your current mainhand item.", sideX, 346, 0xAAAAAA);
        context.drawTextWithShadow(textRenderer, "Styled custom names match exactly.", sideX, 358, 0xAAAAAA);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        List<String> tools = visibleTools();
        int listBottom = height - 18;
        int listWidth = Math.max(120, width - 204);
        int rows = Math.max(1, (listBottom - LIST_TOP) / ROW_HEIGHT);
        if (mouseX >= 12 && mouseX <= 12 + listWidth && mouseY >= LIST_TOP && mouseY <= listBottom) {
            int index = scroll + (int) ((mouseY - LIST_TOP) / ROW_HEIGHT);
            if (index >= 0 && index < tools.size() && index < scroll + rows) {
                selectedToolId = tools.get(index);
                refresh();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        List<String> tools = visibleTools();
        int rows = Math.max(1, (height - 18 - LIST_TOP) / ROW_HEIGHT);
        scroll = HighlightConfig.clamp(scroll - (int) Math.signum(verticalAmount), 0, Math.max(0, tools.size() - rows));
        return true;
    }

    @Override
    public void close() {
        config.save();
        super.close();
    }

    private SmartToolRule selectedRule() {
        return config.ensureSmartTool(selectedToolId);
    }

    private List<String> visibleTools() {
        List<String> tools = new ArrayList<>(config.smartTools.keySet());
        tools.sort(Comparator.comparing(id -> config.ensureSmartTool(id).label));
        return tools;
    }

    private String activeStatus() {
        long seconds = smartManager.remainingSeconds();
        return seconds <= 0 ? "Off" : smartManager.activeToolLabel() + " " + seconds + "s";
    }

    private void refresh() {
        if (durationField == null) {
            return;
        }
        SmartToolRule rule = selectedRule();
        syncing = true;
        durationField.setText(Integer.toString(rule.durationSeconds));
        syncing = false;
        enabledButton.setMessage(Text.literal(rule.enabled ? "Tool: Enabled" : "Tool: Disabled"));
        allMobsButton.setMessage(Text.literal(rule.allMobs ? "All mobs: On" : "All mobs: Custom"));
        allItemsButton.setMessage(Text.literal(rule.allItems ? "All items: On" : "All items: Custom"));
        for (Map.Entry<String, SmartToolRule> entry : config.smartTools.entrySet()) {
            entry.getValue().sanitize();
        }
    }
}

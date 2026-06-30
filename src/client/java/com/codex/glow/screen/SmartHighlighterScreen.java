package com.codex.glow.screen;

import com.codex.glow.config.HighlightConfig;
import com.codex.glow.config.SmartToolRule;
import com.codex.glow.highlight.BlockScanner;
import com.codex.glow.smart.SmartHighlightManager;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class SmartHighlighterScreen extends Screen {
    private static final int ROW_HEIGHT = 18;
    private static final int LIST_TOP = 104;
    private static String selectedToolId = "";
    private static int scroll;

    private final HighlightConfig config;
    private final BlockScanner scanner;
    private final SmartHighlightManager smartManager;
    private TextFieldWidget durationField;
    private ButtonWidget registerButton;
    private ButtonWidget customButton;
    private ButtonWidget enabledButton;
    private ButtonWidget allMobsButton;
    private ButtonWidget allItemsButton;
    private boolean syncing;

    public SmartHighlighterScreen(HighlightConfig config, BlockScanner scanner, SmartHighlightManager smartManager) {
        super(Text.literal("Smart Highlighter"));
        this.config = config;
        this.scanner = scanner;
        this.smartManager = smartManager;
    }

    @Override
    protected void init() {
        HighlighterScreen.rememberSmartTab();
        ensureValidSelection();

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

        customButton = addDrawableChild(ButtonWidget.builder(Text.literal("Create custom tool"), button -> confirmCreateTool())
                .dimensions(12, 54, 130, 20).build());

        registerButton = addDrawableChild(ButtonWidget.builder(Text.literal("Register mainhand"), button -> confirmRegisterMainhand())
                .dimensions(148, 54, 136, 20).build());

        enabledButton = addDrawableChild(ButtonWidget.builder(Text.literal("Tool: Disabled"), button -> {
            SmartToolRule rule = selectedRuleOrNull();
            if (rule == null) {
                return;
            }
            rule.enabled = !rule.enabled;
            config.markDirty();
            refresh();
        }).dimensions(12, 80, 130, 20).build());

        durationField = new TextFieldWidget(textRenderer, 210, 81, 48, 18, Text.literal("Duration"));
        durationField.setMaxLength(3);
        durationField.setChangedListener(value -> {
            if (syncing) {
                return;
            }
            SmartToolRule rule = selectedRuleOrNull();
            if (rule == null) {
                return;
            }
            try {
                rule.durationSeconds = HighlightConfig.clamp(Integer.parseInt(value), 1, 120);
                config.markDirty();
            } catch (NumberFormatException ignored) {
            }
        });
        addDrawableChild(durationField);

        allMobsButton = addDrawableChild(ButtonWidget.builder(Text.literal("All mobs: Custom"), button -> {
            SmartToolRule rule = selectedRuleOrNull();
            if (rule == null) {
                return;
            }
            rule.allMobs = !rule.allMobs;
            if (rule.allMobs) {
                rule.entityTargets.clear();
            }
            config.markDirty();
            refresh();
        }).dimensions(266, 80, 128, 20).build());

        allItemsButton = addDrawableChild(ButtonWidget.builder(Text.literal("All items: Custom"), button -> {
            SmartToolRule rule = selectedRuleOrNull();
            if (rule == null) {
                return;
            }
            rule.allItems = !rule.allItems;
            if (rule.allItems) {
                rule.itemTargets.clear();
            }
            config.markDirty();
            refresh();
        }).dimensions(400, 80, 128, 20).build());

        refresh();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        context.drawTextWithShadow(textRenderer, title, 12, 10, 0xFFFFFF);

        List<String> tools = visibleTools();
        int listWidth = Math.max(160, width - 24);
        int listBottom = height - 18;
        int rows = Math.max(1, (listBottom - LIST_TOP) / ROW_HEIGHT);
        scroll = HighlightConfig.clamp(scroll, 0, Math.max(0, tools.size() - rows));

        if (tools.isEmpty()) {
            context.drawTextWithShadow(textRenderer, "No Smart tools yet. Create one from your mainhand item.", 12, LIST_TOP + 6, 0xAAAAAA);
        }

        for (int i = 0; i < Math.min(rows, tools.size() - scroll); i++) {
            String id = tools.get(scroll + i);
            SmartToolRule rule = config.smartTools.get(id);
            if (rule == null) {
                continue;
            }
            int y = LIST_TOP + i * ROW_HEIGHT;
            boolean selected = id.equals(selectedToolId);
            boolean hovered = mouseX >= 12 && mouseX <= 12 + listWidth && mouseY >= y && mouseY <= y + ROW_HEIGHT - 2;
            context.fill(12, y, 12 + listWidth, y + ROW_HEIGHT - 2, selected ? 0x703A7BFF : (hovered ? 0x50303030 : 0x40111111));
            context.fill(16, y + 4, 26, y + 14, statusColor(rule));
            context.drawTextWithShadow(textRenderer, rule.label, 34, y + 5, labelColor(rule));
            context.drawTextWithShadow(textRenderer, rule.displayName, 162, y + 5, 0x888888);
            drawRowButton(context, rowMobsLeft(), y + 2, 62, "Mobs " + targetCount(rule.allMobs, rule.entityTargets.size()), hasMobTargets(rule),
                    isIn(mouseX, mouseY, rowMobsLeft(), y + 2, 62, 14));
            drawRowButton(context, rowItemsLeft(), y + 2, 64, "Items " + targetCount(rule.allItems, rule.itemTargets.size()), hasItemTargets(rule),
                    isIn(mouseX, mouseY, rowItemsLeft(), y + 2, 64, 14));
            drawRowButton(context, rowBlocksLeft(), y + 2, 70, "Blocks " + rule.blockTargets.size(), !rule.blockTargets.isEmpty(),
                    isIn(mouseX, mouseY, rowBlocksLeft(), y + 2, 70, 14));
            drawRowButton(context, rowDeleteLeft(), y + 2, 54, "Delete", false,
                    isIn(mouseX, mouseY, rowDeleteLeft(), y + 2, 54, 14), 0xFFFF6666);
        }

        SmartToolRule rule = selectedRuleOrNull();
        int statusColor = rule == null ? 0xAAAAAA : (rule.registered ? 0x55FF77 : 0xFF5555);
        String status = rule == null ? "No Smart tool selected" : (rule.registered ? "Registered" : "Unregistered");
        context.drawTextWithShadow(textRenderer, status, 12, height - 46, statusColor);
        context.drawTextWithShadow(textRenderer, "Duration", 150, 86, 0xCFCFCF);
        context.drawTextWithShadow(textRenderer, "Active: " + activeStatus(), 12, height - 32, 0x9BE7FF);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        List<String> tools = visibleTools();
        int listBottom = height - 18;
        int rows = Math.max(1, (listBottom - LIST_TOP) / ROW_HEIGHT);
        if (mouseX >= 12 && mouseX <= width - 12 && mouseY >= LIST_TOP && mouseY <= listBottom) {
            int index = scroll + (int) ((mouseY - LIST_TOP) / ROW_HEIGHT);
            if (index >= 0 && index < tools.size() && index < scroll + rows) {
                String clickedToolId = tools.get(index);
                if (isIn(mouseX, mouseY, rowMobsLeft(), LIST_TOP + (index - scroll) * ROW_HEIGHT + 2, 62, 14)) {
                    selectedToolId = clickedToolId;
                    openTargets(SmartTargetScreen.TargetType.ENTITIES);
                    return true;
                }
                if (isIn(mouseX, mouseY, rowItemsLeft(), LIST_TOP + (index - scroll) * ROW_HEIGHT + 2, 64, 14)) {
                    selectedToolId = clickedToolId;
                    openTargets(SmartTargetScreen.TargetType.ITEMS);
                    return true;
                }
                if (isIn(mouseX, mouseY, rowBlocksLeft(), LIST_TOP + (index - scroll) * ROW_HEIGHT + 2, 70, 14)) {
                    selectedToolId = clickedToolId;
                    openTargets(SmartTargetScreen.TargetType.BLOCKS);
                    return true;
                }
                if (isIn(mouseX, mouseY, rowDeleteLeft(), LIST_TOP + (index - scroll) * ROW_HEIGHT + 2, 54, 14)) {
                    confirmDeleteTool(clickedToolId);
                    return true;
                }
                selectedToolId = clickedToolId;
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
        HighlighterScreen.rememberSmartTab();
        config.save();
        super.close();
    }

    private void confirmCreateTool() {
        if (client == null || client.player == null) {
            return;
        }
        ItemStack stack = client.player.getMainHandStack();
        if (stack.isEmpty()) {
            return;
        }
        confirm(Text.literal("Create Smart tool?"),
                Text.literal("Use current mainhand: " + stack.getName().getString()),
                () -> {
                    selectedToolId = smartManager.createCustomTool(client.player);
                    scroll = 0;
                });
    }

    private void confirmRegisterMainhand() {
        SmartToolRule rule = selectedRuleOrNull();
        if (client == null || client.player == null || rule == null) {
            return;
        }
        ItemStack stack = client.player.getMainHandStack();
        if (stack.isEmpty()) {
            return;
        }
        confirm(Text.literal("Register mainhand?"),
                Text.literal("Use current mainhand for " + rule.label + ": " + stack.getName().getString()),
                () -> smartManager.registerMainhand(client.player, selectedToolId, false));
    }

    private void confirmDeleteTool(String toolId) {
        SmartToolRule rule = config.smartTools.get(toolId);
        if (rule == null) {
            return;
        }
        confirm(Text.literal("Delete Smart tool?"),
                Text.literal("Delete " + rule.label + "?"),
                () -> {
                    smartManager.deleteTool(toolId);
                    if (toolId.equals(selectedToolId)) {
                        selectedToolId = "";
                        ensureValidSelection();
                    }
                });
    }

    private void confirm(Text title, Text message, Runnable confirmedAction) {
        if (client == null) {
            return;
        }
        Screen parent = this;
        client.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                confirmedAction.run();
            }
            if (client != null) {
                client.setScreen(parent);
            }
        }, title, message, Text.literal("Yes"), Text.literal("No")));
    }

    private void openTargets(SmartTargetScreen.TargetType type) {
        if (client != null && selectedRuleOrNull() != null) {
            client.setScreen(new SmartTargetScreen(this, config, selectedToolId, type));
        }
    }

    private SmartToolRule selectedRuleOrNull() {
        ensureValidSelection();
        return selectedToolId.isBlank() ? null : config.smartTools.get(selectedToolId);
    }

    private void ensureValidSelection() {
        if (!selectedToolId.isBlank() && config.smartTools.containsKey(selectedToolId)) {
            return;
        }
        List<String> tools = visibleTools();
        selectedToolId = tools.isEmpty() ? "" : tools.get(0);
        scroll = HighlightConfig.clamp(scroll, 0, Math.max(0, tools.size() - 1));
    }

    private List<String> visibleTools() {
        List<String> tools = new ArrayList<>(config.smartTools.keySet());
        tools.sort(Comparator.comparing(id -> config.smartTools.get(id).label));
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
        ensureValidSelection();
        SmartToolRule rule = selectedRuleOrNull();
        boolean hasSelection = rule != null;
        boolean hasMainhand = client != null && client.player != null && !client.player.getMainHandStack().isEmpty();

        registerButton.active = hasSelection && hasMainhand;
        enabledButton.active = hasSelection;
        durationField.active = hasSelection;
        allMobsButton.active = hasSelection;
        allItemsButton.active = hasSelection;
        customButton.active = hasMainhand;

        syncing = true;
        durationField.setText(rule == null ? "" : Integer.toString(rule.durationSeconds));
        syncing = false;

        enabledButton.setMessage(Text.literal(rule != null && rule.enabled ? "Tool: Enabled" : "Tool: Disabled"));
        allMobsButton.setMessage(allModeText("All mobs", rule != null && rule.allMobs));
        allItemsButton.setMessage(allModeText("All items", rule != null && rule.allItems));
    }

    private static Text allModeText(String label, boolean all) {
        return Text.literal(label + ": ").append(Text.literal(all ? "On" : "Custom").formatted(all ? Formatting.GREEN : Formatting.YELLOW));
    }

    private static int statusColor(SmartToolRule rule) {
        if (!rule.registered) {
            return 0xFF777777;
        }
        return rule.enabled ? 0xFF4DFF7D : 0xFFFF5555;
    }

    private static int labelColor(SmartToolRule rule) {
        if (!rule.registered) {
            return 0xBBBBBB;
        }
        return rule.enabled ? 0xFFFFFF : 0xFF7777;
    }

    private void drawRowButton(DrawContext context, int x, int y, int width, String label, boolean active, boolean hovered) {
        drawRowButton(context, x, y, width, label, active, hovered, active ? 0xFF55FF77 : 0xFFAAAAAA);
    }

    private void drawRowButton(DrawContext context, int x, int y, int width, String label, boolean active, boolean hovered, int textColor) {
        int fill = hovered ? 0x80505050 : 0x60303030;
        int border = hovered ? 0xCCFFFFFF : 0x80555555;
        context.fill(x, y, x + width, y + 14, fill);
        context.fill(x, y, x + width, y + 1, border);
        context.fill(x, y + 13, x + width, y + 14, border);
        context.fill(x, y, x + 1, y + 14, border);
        context.fill(x + width - 1, y, x + width, y + 14, border);
        context.drawTextWithShadow(textRenderer, label, x + 4, y + 3, textColor);
    }

    private int rowDeleteLeft() {
        return width - 70;
    }

    private int rowBlocksLeft() {
        return rowDeleteLeft() - 76;
    }

    private int rowItemsLeft() {
        return rowBlocksLeft() - 70;
    }

    private int rowMobsLeft() {
        return rowItemsLeft() - 68;
    }

    private static boolean isIn(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private static String targetCount(boolean all, int count) {
        return all ? "All" : Integer.toString(count);
    }

    private static boolean hasMobTargets(SmartToolRule rule) {
        return rule.allMobs || !rule.entityTargets.isEmpty();
    }

    private static boolean hasItemTargets(SmartToolRule rule) {
        return rule.allItems || !rule.itemTargets.isEmpty();
    }
}

package com.codex.glow.screen;

import com.codex.glow.config.BlockRule;
import com.codex.glow.config.EntityRule;
import com.codex.glow.config.HighlightConfig;
import com.codex.glow.highlight.BlockScanner;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class HighlighterScreen extends Screen {
    private static final int ROW_HEIGHT = 18;
    private static final int LIST_TOP = 58;

    private final HighlightConfig config;
    private final BlockScanner scanner;

    private Tab tab = Tab.ENTITIES;
    private TextFieldWidget searchField;
    private TextFieldWidget colorField;
    private TextFieldWidget rangeField;
    private TextFieldWidget maxHighlightsField;
    private ButtonWidget toggleButton;
    private ButtonWidget wallsButton;
    private ButtonWidget modeButton;
    private boolean syncingEditor;
    private Identifier selectedId = Identifier.of("minecraft", "zombie");
    private int scroll;

    public HighlighterScreen(Text title, HighlightConfig config, BlockScanner scanner) {
        super(title);
        this.config = config;
        this.scanner = scanner;
    }

    @Override
    protected void init() {
        int sideX = width - 152;

        addDrawableChild(ButtonWidget.builder(Text.literal("Entities"), button -> {
            tab = Tab.ENTITIES;
            selectedId = Identifier.of("minecraft", "zombie");
            scroll = 0;
            refreshEditor();
        }).dimensions(12, 26, 82, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Blocks"), button -> {
            tab = Tab.BLOCKS;
            selectedId = Identifier.of("minecraft", "chest");
            scroll = 0;
            refreshEditor();
        }).dimensions(100, 26, 82, 20).build());

        searchField = new TextFieldWidget(textRenderer, 12, 52, Math.max(120, width - 184), 18, Text.literal("Search"));
        searchField.setPlaceholder(Text.literal("Search"));
        searchField.setChangedListener(value -> scroll = 0);
        addDrawableChild(searchField);

        toggleButton = addDrawableChild(ButtonWidget.builder(Text.literal("Toggle"), button -> {
            if (tab == Tab.ENTITIES) {
                EntityRule rule = config.ensureEntityRule(selectedId);
                rule.enabled = !rule.enabled;
            } else {
                BlockRule rule = config.ensureBlockRule(selectedId);
                rule.enabled = !rule.enabled;
                scanner.requestRescan();
            }
            config.markDirty();
            refreshEditor();
        }).dimensions(sideX, 58, 140, 20).build());

        colorField = new TextFieldWidget(textRenderer, sideX, 102, 140, 18, Text.literal("Color"));
        colorField.setMaxLength(7);
        colorField.setChangedListener(value -> {
            if (syncingEditor) {
                return;
            }
            if (!value.matches("^#[0-9a-fA-F]{6}$")) {
                return;
            }
            if (tab == Tab.ENTITIES) {
                config.ensureEntityRule(selectedId).color = value.toUpperCase(Locale.ROOT);
            } else {
                config.ensureBlockRule(selectedId).color = value.toUpperCase(Locale.ROOT);
                scanner.requestRescan();
            }
            config.markDirty();
        });
        addDrawableChild(colorField);

        rangeField = new TextFieldWidget(textRenderer, sideX, 144, 140, 18, Text.literal("Range"));
        rangeField.setMaxLength(3);
        rangeField.setChangedListener(value -> {
            if (syncingEditor) {
                return;
            }
            try {
                int range = HighlightConfig.clamp(Integer.parseInt(value), 1, 512);
                if (tab == Tab.ENTITIES) {
                    config.ensureEntityRule(selectedId).range = range;
                } else {
                    config.ensureBlockRule(selectedId).range = range;
                    scanner.requestRescan();
                }
                config.markDirty();
            } catch (NumberFormatException ignored) {
            }
        });
        addDrawableChild(rangeField);

        addDrawableChild(ButtonWidget.builder(Text.literal("White"), button -> applyColor("#FFFFFF"))
                .dimensions(sideX, 166, 66, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Green"), button -> applyColor("#00FF55"))
                .dimensions(sideX + 74, 166, 66, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Gold"), button -> applyColor("#FFD700"))
                .dimensions(sideX, 188, 66, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Cyan"), button -> applyColor("#00FFFF"))
                .dimensions(sideX + 74, 188, 66, 18).build());

        wallsButton = addDrawableChild(ButtonWidget.builder(Text.literal("Through walls"), button -> {
            if (tab == Tab.ENTITIES) {
                EntityRule rule = config.ensureEntityRule(selectedId);
                rule.throughWalls = !rule.throughWalls;
            } else {
                BlockRule rule = config.ensureBlockRule(selectedId);
                rule.throughWalls = !rule.throughWalls;
                scanner.requestRescan();
            }
            config.markDirty();
            refreshEditor();
        }).dimensions(sideX, 214, 140, 20).build());

        modeButton = addDrawableChild(ButtonWidget.builder(Text.literal("Mode"), button -> {
            BlockRule rule = config.ensureBlockRule(selectedId);
            rule.mode = rule.mode.next();
            scanner.requestRescan();
            config.markDirty();
            refreshEditor();
        }).dimensions(sideX, 240, 140, 20).build());

        maxHighlightsField = new TextFieldWidget(textRenderer, sideX, 284, 140, 18, Text.literal("Max highlights"));
        maxHighlightsField.setMaxLength(5);
        maxHighlightsField.setChangedListener(value -> {
            if (syncingEditor || tab != Tab.BLOCKS) {
                return;
            }
            try {
                BlockRule rule = config.ensureBlockRule(selectedId);
                rule.maxHighlights = HighlightConfig.clamp(Integer.parseInt(value), 1, 20000);
                scanner.requestRescan();
                config.markDirty();
            } catch (NumberFormatException ignored) {
            }
        });
        addDrawableChild(maxHighlightsField);

        refreshEditor();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);

        context.drawTextWithShadow(textRenderer, title, 12, 10, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, "Selected", width - 152, 38, 0xCFCFCF);
        context.drawTextWithShadow(textRenderer, selectedId.toString(), width - 152, 82, 0xFFFFFF);
        context.drawTextWithShadow(textRenderer, "Color", width - 152, 92, 0xCFCFCF);
        context.drawTextWithShadow(textRenderer, "Range", width - 152, 134, 0xCFCFCF);
        context.drawTextWithShadow(textRenderer, "Presets", width - 152, 156, 0xCFCFCF);
        if (tab == Tab.BLOCKS) {
            context.drawTextWithShadow(textRenderer, "Max highlights", width - 152, 274, 0xCFCFCF);
        }

        List<Identifier> visible = visibleIds();
        int listBottom = height - 18;
        int rows = Math.max(1, (listBottom - LIST_TOP) / ROW_HEIGHT);
        int maxRows = Math.min(rows, visible.size() - scroll);
        int listWidth = Math.max(120, width - 184);

        for (int i = 0; i < maxRows; i++) {
            Identifier id = visible.get(scroll + i);
            int y = LIST_TOP + i * ROW_HEIGHT;
            boolean selected = id.equals(selectedId);
            boolean enabled = isEnabled(id);
            int rowColor = selected ? 0x703A7BFF : 0x40111111;
            context.fill(12, y, 12 + listWidth, y + ROW_HEIGHT - 2, rowColor);
            context.fill(16, y + 4, 26, y + 14, enabled ? 0xFF4DFF7D : 0xFF555555);
            context.drawTextWithShadow(textRenderer, HighlightConfig.displayName(id), 32, y + 5, enabled ? 0xFFFFFF : 0xBBBBBB);
            context.drawTextWithShadow(textRenderer, id.toString(), Math.min(width - 334, 168), y + 5, 0x777777);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        List<Identifier> visible = visibleIds();
        int listBottom = height - 18;
        int rows = Math.max(1, (listBottom - LIST_TOP) / ROW_HEIGHT);
        int listWidth = Math.max(120, width - 184);

        if (mouseX >= 12 && mouseX <= 12 + listWidth && mouseY >= LIST_TOP && mouseY <= listBottom) {
            int index = scroll + (int) ((mouseY - LIST_TOP) / ROW_HEIGHT);
            if (index >= 0 && index < visible.size() && index < scroll + rows) {
                selectedId = visible.get(index);
                if (mouseX >= 16 && mouseX <= 28) {
                    if (tab == Tab.ENTITIES) {
                        EntityRule rule = config.ensureEntityRule(selectedId);
                        rule.enabled = !rule.enabled;
                    } else {
                        BlockRule rule = config.ensureBlockRule(selectedId);
                        rule.enabled = !rule.enabled;
                        scanner.requestRescan();
                    }
                    config.markDirty();
                }
                refreshEditor();
                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        List<Identifier> visible = visibleIds();
        int listBottom = height - 18;
        int rows = Math.max(1, (listBottom - LIST_TOP) / ROW_HEIGHT);
        int maxScroll = Math.max(0, visible.size() - rows);
        scroll = HighlightConfig.clamp(scroll - (int) Math.signum(verticalAmount), 0, maxScroll);
        return true;
    }

    @Override
    public void close() {
        config.save();
        super.close();
    }

    private List<Identifier> visibleIds() {
        String query = searchField == null ? "" : searchField.getText().toLowerCase(Locale.ROOT).trim();
        List<Identifier> ids = new ArrayList<>();
        if (tab == Tab.ENTITIES) {
            Registries.ENTITY_TYPE.getIds().forEach(ids::add);
        } else {
            Registries.BLOCK.getIds().forEach(ids::add);
        }

        return ids.stream()
                .filter(id -> query.isEmpty() || id.toString().toLowerCase(Locale.ROOT).contains(query)
                        || HighlightConfig.displayName(id).toLowerCase(Locale.ROOT).contains(query))
                .sorted(Comparator.comparing(Identifier::toString))
                .toList();
    }

    private boolean isEnabled(Identifier id) {
        if (tab == Tab.ENTITIES) {
            EntityRule rule = config.entities.get(id.toString());
            return rule != null && rule.enabled;
        }
        BlockRule rule = config.blocks.get(id.toString());
        return rule != null && rule.enabled;
    }

    private void refreshEditor() {
        if (toggleButton == null) {
            return;
        }

        syncingEditor = true;
        if (tab == Tab.ENTITIES) {
            EntityRule rule = config.entities.get(selectedId.toString());
            boolean enabled = rule != null && rule.enabled;
            String color = rule == null ? HighlightConfig.DEFAULT_ENTITY_COLOR : rule.color;
            int range = rule == null ? HighlightConfig.DEFAULT_RANGE : rule.range;
            boolean throughWalls = rule == null || rule.throughWalls;
            toggleButton.setMessage(Text.literal(enabled ? "Enabled" : "Disabled"));
            colorField.setText(color);
            rangeField.setText(Integer.toString(range));
            wallsButton.setMessage(Text.literal(throughWalls ? "Through walls: On" : "Through walls: Off"));
            modeButton.visible = false;
            maxHighlightsField.visible = false;
        } else {
            BlockRule rule = config.blocks.get(selectedId.toString());
            boolean enabled = rule != null && rule.enabled;
            String color = rule == null ? HighlightConfig.DEFAULT_BLOCK_COLOR : rule.color;
            int range = rule == null ? HighlightConfig.DEFAULT_RANGE : rule.range;
            boolean throughWalls = rule == null || rule.throughWalls;
            String mode = rule == null ? "box" : rule.mode.name().toLowerCase(Locale.ROOT);
            int maxHighlights = rule == null ? 2048 : rule.maxHighlights;
            toggleButton.setMessage(Text.literal(enabled ? "Enabled" : "Disabled"));
            colorField.setText(color);
            rangeField.setText(Integer.toString(range));
            wallsButton.setMessage(Text.literal(throughWalls ? "Through walls: On" : "Through walls: Off"));
            modeButton.setMessage(Text.literal("Mode: " + mode));
            modeButton.visible = true;
            maxHighlightsField.setText(Integer.toString(maxHighlights));
            maxHighlightsField.visible = true;
        }
        syncingEditor = false;
    }

    private void applyColor(String color) {
        colorField.setText(color);
        if (tab == Tab.ENTITIES) {
            config.ensureEntityRule(selectedId).color = color;
        } else {
            config.ensureBlockRule(selectedId).color = color;
            scanner.requestRescan();
        }
        config.markDirty();
        refreshEditor();
    }

    private enum Tab {
        ENTITIES,
        BLOCKS
    }
}

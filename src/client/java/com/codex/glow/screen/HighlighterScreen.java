package com.codex.glow.screen;

import com.codex.glow.config.BlockRule;
import com.codex.glow.config.EntityRule;
import com.codex.glow.config.HighlightConfig;
import com.codex.glow.config.ItemRule;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class HighlighterScreen extends Screen {
    private static final int ROW_HEIGHT = 18;
    private static final int LIST_TOP = 76;
    private static final Identifier ALL_DROPPED_ITEMS = Identifier.of("minecraft", "item");
    private static final Map<Tab, Identifier> REMEMBERED_SELECTED = new EnumMap<>(Tab.class);
    private static final Map<Tab, Integer> REMEMBERED_SCROLL = new EnumMap<>(Tab.class);
    private static Tab rememberedTab = Tab.ENTITIES;
    private static String rememberedSearch = "";
    private static final Set<String> NOISY_BLOCKS = Set.of(
            "minecraft:air",
            "minecraft:cave_air",
            "minecraft:void_air",
            "minecraft:water",
            "minecraft:lava",
            "minecraft:stone",
            "minecraft:dirt",
            "minecraft:grass_block",
            "minecraft:short_grass"
    );

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
    private ButtonWidget showNoisyBlocksButton;
    private ButtonWidget loadedChunkRangeButton;
    private ButtonWidget allDroppedItemsButton;
    private ButtonWidget itemAutoColorButton;
    private boolean syncingEditor;
    private boolean syncingSearch;
    private Identifier selectedId = Identifier.of("minecraft", "zombie");
    private int scroll;

    public HighlighterScreen(Text title, HighlightConfig config, BlockScanner scanner) {
        super(title);
        this.config = config;
        this.scanner = scanner;
        this.tab = rememberedTab;
        this.selectedId = rememberedSelectedId(tab);
        this.scroll = REMEMBERED_SCROLL.getOrDefault(tab, 0);
    }

    @Override
    protected void init() {
        int sideX = width - 152;

        addDrawableChild(ButtonWidget.builder(Text.literal("Entities"), button -> {
            switchTab(Tab.ENTITIES);
            refreshEditor();
        }).dimensions(12, 26, 76, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Blocks"), button -> {
            switchTab(Tab.BLOCKS);
            refreshEditor();
        }).dimensions(94, 26, 76, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Items"), button -> {
            switchTab(Tab.ITEMS);
            refreshEditor();
        }).dimensions(176, 26, 76, 20).build());

        searchField = new TextFieldWidget(textRenderer, 12, 52, Math.max(120, width - 184), 18, Text.literal("Search"));
        searchField.setPlaceholder(Text.literal("Search"));
        searchField.setChangedListener(value -> {
            rememberedSearch = value;
            if (!syncingSearch) {
                scroll = 0;
                rememberCurrentState();
            }
        });
        addDrawableChild(searchField);
        syncingSearch = true;
        searchField.setText(rememberedSearch);
        syncingSearch = false;

        toggleButton = addDrawableChild(ButtonWidget.builder(Text.literal("Toggle"), button -> {
            if (tab == Tab.ENTITIES) {
                EntityRule rule = config.ensureEntityRule(selectedId);
                rule.enabled = !rule.enabled;
            } else if (tab == Tab.BLOCKS) {
                BlockRule rule = config.ensureBlockRule(selectedId);
                rule.enabled = !rule.enabled;
                scanner.requestPriorityRescan();
            } else {
                ItemRule rule = config.ensureItemRule(selectedId);
                rule.enabled = !rule.enabled;
            }
            config.markDirty();
            rememberCurrentState();
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
            } else if (tab == Tab.BLOCKS) {
                config.ensureBlockRule(selectedId).color = value.toUpperCase(Locale.ROOT);
                scanner.requestPriorityRescan();
            } else {
                ItemRule rule = config.ensureItemRule(selectedId);
                rule.color = value.toUpperCase(Locale.ROOT);
                rule.autoColor = false;
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
                } else if (tab == Tab.BLOCKS) {
                    config.ensureBlockRule(selectedId).range = range;
                    scanner.requestPriorityRescan();
                } else {
                    config.ensureItemRule(selectedId).range = range;
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
            } else if (tab == Tab.BLOCKS) {
                BlockRule rule = config.ensureBlockRule(selectedId);
                rule.throughWalls = !rule.throughWalls;
            } else {
                ItemRule rule = config.ensureItemRule(selectedId);
                rule.throughWalls = !rule.throughWalls;
            }
            config.markDirty();
            refreshEditor();
        }).dimensions(sideX, 214, 140, 20).build());

        modeButton = addDrawableChild(ButtonWidget.builder(Text.literal("Mode"), button -> {
            BlockRule rule = config.ensureBlockRule(selectedId);
            rule.mode = rule.mode.next();
            scanner.requestPriorityRescan();
            config.markDirty();
            refreshEditor();
        }).dimensions(sideX, 240, 140, 20).build());

        showNoisyBlocksButton = addDrawableChild(ButtonWidget.builder(Text.literal("Show noisy blocks"), button -> {
            config.showNoisyBlocks = !config.showNoisyBlocks;
            scroll = 0;
            config.markDirty();
            refreshEditor();
        }).dimensions(sideX, 266, 140, 20).build());

        loadedChunkRangeButton = addDrawableChild(ButtonWidget.builder(Text.literal("Loaded range"), button -> {
            config.useLoadedChunkRange = !config.useLoadedChunkRange;
            scanner.requestPriorityRescan();
            config.markDirty();
            refreshEditor();
        }).dimensions(sideX, 292, 140, 20).build());

        allDroppedItemsButton = addDrawableChild(ButtonWidget.builder(Text.literal("All dropped"), button -> {
            config.allDroppedItems.enabled = !config.allDroppedItems.enabled;
            config.allDroppedItems.autoColor = true;
            config.markDirty();
            refreshEditor();
        }).dimensions(sideX, 240, 140, 20).build());

        itemAutoColorButton = addDrawableChild(ButtonWidget.builder(Text.literal("Auto color"), button -> {
            ItemRule rule = config.ensureItemRule(selectedId);
            rule.autoColor = !rule.autoColor;
            config.markDirty();
            refreshEditor();
        }).dimensions(sideX, 266, 140, 20).build());

        maxHighlightsField = new TextFieldWidget(textRenderer, sideX, 326, 140, 18, Text.literal("Max highlights"));
        maxHighlightsField.setMaxLength(5);
        maxHighlightsField.setChangedListener(value -> {
            if (syncingEditor || tab != Tab.BLOCKS) {
                return;
            }
            try {
                BlockRule rule = config.ensureBlockRule(selectedId);
                rule.maxHighlights = HighlightConfig.clamp(Integer.parseInt(value), 1, 20000);
                scanner.requestPriorityRescan();
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
            context.drawTextWithShadow(textRenderer, "Block options", width - 152, 258, 0xCFCFCF);
            context.drawTextWithShadow(textRenderer, "Max highlights", width - 152, 316, 0xCFCFCF);
        } else if (tab == Tab.ITEMS) {
            context.drawTextWithShadow(textRenderer, "Dropped item stacks", width - 152, 232, 0xCFCFCF);
        }

        List<Identifier> visible = visibleIds();
        int listBottom = height - 18;
        int rows = Math.max(1, (listBottom - LIST_TOP) / ROW_HEIGHT);
        clampScroll(visible, rows);
        int maxRows = Math.min(rows, Math.max(0, visible.size() - scroll));
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
        clampScroll(visible, rows);

        if (mouseX >= 12 && mouseX <= 12 + listWidth && mouseY >= LIST_TOP && mouseY <= listBottom) {
            int index = scroll + (int) ((mouseY - LIST_TOP) / ROW_HEIGHT);
            if (index >= 0 && index < visible.size() && index < scroll + rows) {
                selectedId = visible.get(index);
                if (mouseX >= 16 && mouseX <= 28) {
                    if (tab == Tab.ENTITIES) {
                        EntityRule rule = config.ensureEntityRule(selectedId);
                        rule.enabled = !rule.enabled;
                    } else if (tab == Tab.BLOCKS) {
                        BlockRule rule = config.ensureBlockRule(selectedId);
                        rule.enabled = !rule.enabled;
                        scanner.requestPriorityRescan();
                    } else {
                        ItemRule rule = config.ensureItemRule(selectedId);
                        rule.enabled = !rule.enabled;
                    }
                    config.markDirty();
                }
                rememberCurrentState();
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
        rememberCurrentState();
        return true;
    }

    @Override
    public void close() {
        rememberCurrentState();
        config.save();
        super.close();
    }

    private List<Identifier> visibleIds() {
        String query = searchField == null ? "" : searchField.getText().toLowerCase(Locale.ROOT).trim();
        List<Identifier> ids = new ArrayList<>();
        if (tab == Tab.ENTITIES) {
            Registries.ENTITY_TYPE.getIds().forEach(ids::add);
        } else if (tab == Tab.BLOCKS) {
            Registries.BLOCK.getIds().forEach(ids::add);
        } else {
            Registries.ITEM.getIds().forEach(ids::add);
        }

        return ids.stream()
                .filter(id -> tab != Tab.ENTITIES || !id.equals(ALL_DROPPED_ITEMS))
                .filter(id -> tab != Tab.BLOCKS || config.showNoisyBlocks || !query.isEmpty() || !NOISY_BLOCKS.contains(id.toString()))
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
        if (tab == Tab.ITEMS) {
            ItemRule rule = config.items.get(id.toString());
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
            showNoisyBlocksButton.visible = false;
            loadedChunkRangeButton.visible = false;
            allDroppedItemsButton.visible = false;
            itemAutoColorButton.visible = false;
            maxHighlightsField.visible = false;
        } else if (tab == Tab.BLOCKS) {
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
            showNoisyBlocksButton.setMessage(Text.literal(config.showNoisyBlocks ? "Noisy blocks: Shown" : "Noisy blocks: Hidden"));
            showNoisyBlocksButton.visible = true;
            loadedChunkRangeButton.setMessage(Text.literal(config.useLoadedChunkRange ? "Loaded chunks range: On" : "Loaded chunks range: Off"));
            loadedChunkRangeButton.visible = true;
            allDroppedItemsButton.visible = false;
            itemAutoColorButton.visible = false;
            maxHighlightsField.setText(Integer.toString(maxHighlights));
            maxHighlightsField.visible = true;
        } else {
            ItemRule rule = config.items.get(selectedId.toString());
            boolean enabled = rule != null && rule.enabled;
            String color = rule == null ? HighlightConfig.DEFAULT_ITEM_COLOR : rule.color;
            int range = rule == null ? HighlightConfig.DEFAULT_RANGE : rule.range;
            boolean throughWalls = rule == null || rule.throughWalls;
            boolean autoColor = rule == null || rule.autoColor;
            toggleButton.setMessage(Text.literal(enabled ? "Enabled" : "Disabled"));
            colorField.setText(color);
            rangeField.setText(Integer.toString(range));
            wallsButton.setMessage(Text.literal(throughWalls ? "Through walls: On" : "Through walls: Off"));
            modeButton.visible = false;
            showNoisyBlocksButton.visible = false;
            loadedChunkRangeButton.visible = false;
            allDroppedItemsButton.setMessage(Text.literal(config.allDroppedItems.enabled ? "All dropped: On" : "All dropped: Off"));
            allDroppedItemsButton.visible = true;
            itemAutoColorButton.setMessage(Text.literal(autoColor ? "Auto color: On" : "Auto color: Off"));
            itemAutoColorButton.visible = true;
            maxHighlightsField.visible = false;
        }
        syncingEditor = false;
    }

    private void applyColor(String color) {
        colorField.setText(color);
        if (tab == Tab.ENTITIES) {
            config.ensureEntityRule(selectedId).color = color;
        } else if (tab == Tab.BLOCKS) {
            config.ensureBlockRule(selectedId).color = color;
            scanner.requestPriorityRescan();
        } else {
            ItemRule rule = config.ensureItemRule(selectedId);
            rule.color = color;
            rule.autoColor = false;
        }
        config.markDirty();
        refreshEditor();
    }

    private void switchTab(Tab nextTab) {
        rememberCurrentState();
        tab = nextTab;
        rememberedTab = nextTab;
        selectedId = rememberedSelectedId(nextTab);
        scroll = REMEMBERED_SCROLL.getOrDefault(nextTab, 0);
    }

    private void rememberCurrentState() {
        rememberedTab = tab;
        REMEMBERED_SELECTED.put(tab, selectedId);
        REMEMBERED_SCROLL.put(tab, scroll);
        if (searchField != null) {
            rememberedSearch = searchField.getText();
        }
    }

    private static Identifier rememberedSelectedId(Tab tab) {
        return REMEMBERED_SELECTED.computeIfAbsent(tab, current -> switch (current) {
            case ENTITIES -> Identifier.of("minecraft", "zombie");
            case BLOCKS -> Identifier.of("minecraft", "chest");
            case ITEMS -> Identifier.of("minecraft", "diamond");
        });
    }

    private void clampScroll(List<Identifier> visible, int rows) {
        int maxScroll = Math.max(0, visible.size() - rows);
        scroll = HighlightConfig.clamp(scroll, 0, maxScroll);
    }

    private enum Tab {
        ENTITIES,
        BLOCKS,
        ITEMS
    }
}

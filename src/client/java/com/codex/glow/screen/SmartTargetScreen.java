package com.codex.glow.screen;

import com.codex.glow.config.HighlightConfig;
import com.codex.glow.config.SmartToolRule;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class SmartTargetScreen extends Screen {
    private static final int ROW_HEIGHT = 18;
    private static final int LIST_TOP = 78;

    private final Screen parent;
    private final HighlightConfig config;
    private final String toolId;
    private final TargetType type;
    private TextFieldWidget searchField;
    private int scroll;

    public SmartTargetScreen(Screen parent, HighlightConfig config, String toolId, TargetType type) {
        super(Text.literal("Smart targets"));
        this.parent = parent;
        this.config = config;
        this.toolId = toolId;
        this.type = type;
    }

    @Override
    protected void init() {
        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), button -> client.setScreen(parent))
                .dimensions(12, 26, 70, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Check all"), button -> {
            checkAll();
            config.markDirty();
        }).dimensions(width - 174, 26, 78, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Uncheck all"), button -> {
            uncheckAll();
            config.markDirty();
        }).dimensions(width - 90, 26, 78, 20).build());
        searchField = new TextFieldWidget(textRenderer, 12, 52, Math.max(120, width - 24), 18, Text.literal("Search"));
        searchField.setPlaceholder(Text.literal("Search"));
        searchField.setChangedListener(value -> scroll = 0);
        addDrawableChild(searchField);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        context.drawTextWithShadow(textRenderer, title.copy().append(" - ").append(type.title), 12, 10, 0xFFFFFF);

        List<Identifier> ids = visibleIds();
        int listBottom = height - 18;
        int rows = Math.max(1, (listBottom - LIST_TOP) / ROW_HEIGHT);
        scroll = HighlightConfig.clamp(scroll, 0, Math.max(0, ids.size() - rows));
        for (int i = 0; i < Math.min(rows, ids.size() - scroll); i++) {
            Identifier id = ids.get(scroll + i);
            int y = LIST_TOP + i * ROW_HEIGHT;
            boolean checked = isChecked(id);
            boolean hovered = mouseX >= 12 && mouseX <= width - 12 && mouseY >= y && mouseY <= y + ROW_HEIGHT - 2;
            context.fill(12, y, width - 12, y + ROW_HEIGHT - 2, hovered ? 0x50303030 : 0x40111111);
            context.fill(16, y + 4, 26, y + 14, checked ? 0xFF4DFF7D : 0xFF555555);
            context.drawTextWithShadow(textRenderer, HighlightConfig.displayName(id), 32, y + 5, checked ? 0xFFFFFF : 0xBBBBBB);
            context.drawTextWithShadow(textRenderer, id.toString(), 168, y + 5, 0x777777);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        List<Identifier> ids = visibleIds();
        int rows = Math.max(1, (height - 18 - LIST_TOP) / ROW_HEIGHT);
        if (mouseX >= 12 && mouseX <= width - 12 && mouseY >= LIST_TOP && mouseY <= height - 18) {
            int index = scroll + (int) ((mouseY - LIST_TOP) / ROW_HEIGHT);
            if (index >= 0 && index < ids.size() && index < scroll + rows) {
                toggle(ids.get(index));
                config.markDirty();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        List<Identifier> ids = visibleIds();
        int rows = Math.max(1, (height - 18 - LIST_TOP) / ROW_HEIGHT);
        scroll = HighlightConfig.clamp(scroll - (int) Math.signum(verticalAmount), 0, Math.max(0, ids.size() - rows));
        return true;
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    private List<Identifier> visibleIds() {
        String query = searchField == null ? "" : searchField.getText().toLowerCase(Locale.ROOT).trim();
        return allIds().stream()
                .filter(id -> query.isEmpty() || id.toString().toLowerCase(Locale.ROOT).contains(query)
                        || HighlightConfig.displayName(id).toLowerCase(Locale.ROOT).contains(query))
                .sorted(Comparator.comparing((Identifier id) -> !isChecked(id)).thenComparing(Identifier::toString))
                .toList();
    }

    private List<Identifier> allIds() {
        List<Identifier> ids = new ArrayList<>();
        if (type == TargetType.ENTITIES) {
            Registries.ENTITY_TYPE.getIds().forEach(id -> {
                String text = id.toString();
                if (!"minecraft:item".equals(text) && !"minecraft:player".equals(text)) {
                    ids.add(id);
                }
            });
        } else if (type == TargetType.ITEMS) {
            Registries.ITEM.getIds().forEach(ids::add);
        } else {
            Registries.BLOCK.getIds().forEach(ids::add);
        }
        ids.sort(Comparator.comparing(Identifier::toString));
        return ids;
    }

    private boolean isChecked(Identifier id) {
        SmartToolRule rule = rule();
        return switch (type) {
            case ENTITIES -> rule.allMobs || rule.entityTargets.contains(id.toString());
            case ITEMS -> rule.allItems || rule.itemTargets.contains(id.toString());
            case BLOCKS -> rule.blockTargets.contains(id.toString());
        };
    }

    private void toggle(Identifier id) {
        SmartToolRule rule = rule();
        if (type == TargetType.ENTITIES && rule.allMobs) {
            materializeAll(rule.entityTargets);
            rule.allMobs = false;
        } else if (type == TargetType.ITEMS && rule.allItems) {
            materializeAll(rule.itemTargets);
            rule.allItems = false;
        }
        Set<String> target = switch (type) {
            case ENTITIES -> rule.entityTargets;
            case ITEMS -> rule.itemTargets;
            case BLOCKS -> rule.blockTargets;
        };
        String key = id.toString();
        if (!target.remove(key)) {
            target.add(key);
        }
    }

    private void checkAll() {
        SmartToolRule rule = rule();
        if (type == TargetType.ENTITIES) {
            rule.allMobs = true;
            rule.entityTargets.clear();
        } else if (type == TargetType.ITEMS) {
            rule.allItems = true;
            rule.itemTargets.clear();
        } else {
            materializeAll(rule.blockTargets);
        }
    }

    private void uncheckAll() {
        SmartToolRule rule = rule();
        if (type == TargetType.ENTITIES) {
            rule.allMobs = false;
            rule.entityTargets.clear();
        } else if (type == TargetType.ITEMS) {
            rule.allItems = false;
            rule.itemTargets.clear();
        } else {
            rule.blockTargets.clear();
        }
    }

    private void materializeAll(Set<String> target) {
        target.clear();
        for (Identifier id : allIds()) {
            target.add(id.toString());
        }
    }

    private SmartToolRule rule() {
        return config.ensureSmartTool(toolId);
    }

    public enum TargetType {
        ENTITIES("Mobs"),
        ITEMS("Items"),
        BLOCKS("Blocks");

        private final String title;

        TargetType(String title) {
            this.title = title;
        }
    }
}

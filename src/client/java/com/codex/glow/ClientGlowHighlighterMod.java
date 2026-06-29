package com.codex.glow;

import com.codex.glow.config.HighlightConfig;
import com.codex.glow.highlight.BlockScanner;
import com.codex.glow.render.BlockOutlineRenderer;
import com.codex.glow.screen.HighlighterScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

public final class ClientGlowHighlighterMod implements ClientModInitializer {
    public static final String MOD_ID = "general-highlighter";

    private static HighlightConfig config;
    private static BlockScanner blockScanner;
    private static KeyBinding openMenuKey;

    @Override
    public void onInitializeClient() {
        config = HighlightConfig.load();
        blockScanner = new BlockScanner(config);
        openMenuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.general-highlighter.open_menu",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                "category.general-highlighter"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> BlockOutlineRenderer.render(context, blockScanner));
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> blockScanner.onChunkLoaded(world, chunk.getPos()));
        ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> blockScanner.onChunkUnloaded(chunk.getPos()));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> config.save());
    }

    private void onClientTick(MinecraftClient client) {
        while (openMenuKey.wasPressed()) {
            client.setScreen(new HighlighterScreen(Text.literal("Highlighter"), config, blockScanner));
        }

        if (client.world == null || client.player == null) {
            blockScanner.clear();
            return;
        }

        blockScanner.tick(client);
        config.saveIfDirty();
    }

    public static boolean shouldGlowEntity(Entity entity) {
        if (config == null || entity == null || entity.getWorld() == null) {
            return false;
        }

        Identifier id = Registries.ENTITY_TYPE.getId(entity.getType());
        return config.shouldHighlightEntity(id, entity);
    }

    public static Integer getEntityGlowColor(Entity entity) {
        if (config == null || entity == null) {
            return null;
        }

        Identifier id = Registries.ENTITY_TYPE.getId(entity.getType());
        return config.getEntityColor(id, entity);
    }

    public static void onClientBlockUpdated(BlockPos pos) {
        if (blockScanner != null && pos != null) {
            blockScanner.onBlockUpdated(pos);
        }
    }
}

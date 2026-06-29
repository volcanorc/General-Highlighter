package com.codex.glow.render;

import com.codex.glow.config.BlockRenderMode;
import com.codex.glow.highlight.BlockHighlight;
import com.codex.glow.highlight.BlockScanner;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

public final class BlockOutlineRenderer {
    private BlockOutlineRenderer() {
    }

    public static void render(WorldRenderContext context, BlockScanner scanner) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || context.consumers() == null) {
            return;
        }

        MatrixStack matrices = context.matrixStack();
        Vec3d camera = context.camera().getPos();
        VertexConsumer lines = context.consumers().getBuffer(RenderLayer.getLines());
        List<BlockHighlight> highlights = scanner.getHighlights(client.player);

        matrices.push();
        matrices.translate(-camera.x, -camera.y, -camera.z);

        for (BlockHighlight highlight : highlights) {
            if (highlight.rule().mode == BlockRenderMode.CLUSTER) {
                continue;
            }
            if (highlight.rule().throughWalls) {
                RenderSystem.disableDepthTest();
            } else {
                RenderSystem.enableDepthTest();
            }

            int color = highlight.color();
            float red = ((color >> 16) & 0xFF) / 255.0f;
            float green = ((color >> 8) & 0xFF) / 255.0f;
            float blue = (color & 0xFF) / 255.0f;
            Box box = new Box(highlight.pos()).expand(0.002);
            WorldRenderer.drawBox(matrices, lines, box, red, green, blue, 0.9f);
        }

        for (Cluster cluster : buildClusters(highlights)) {
            if (cluster.throughWalls) {
                RenderSystem.disableDepthTest();
            } else {
                RenderSystem.enableDepthTest();
            }

            float red = ((cluster.color >> 16) & 0xFF) / 255.0f;
            float green = ((cluster.color >> 8) & 0xFF) / 255.0f;
            float blue = (cluster.color & 0xFF) / 255.0f;
            WorldRenderer.drawBox(matrices, lines, cluster.box.expand(0.01), red, green, blue, 0.95f);
        }

        RenderSystem.enableDepthTest();
        matrices.pop();
    }

    private static List<Cluster> buildClusters(List<BlockHighlight> highlights) {
        Map<BlockPos, BlockHighlight> remaining = new HashMap<>();
        for (BlockHighlight highlight : highlights) {
            if (highlight.rule().mode == BlockRenderMode.CLUSTER) {
                remaining.put(highlight.pos(), highlight);
            }
        }

        List<Cluster> clusters = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> queue = new ArrayDeque<>();

        for (BlockPos start : remaining.keySet()) {
            if (visited.contains(start)) {
                continue;
            }

            BlockHighlight first = remaining.get(start);
            int minX = start.getX();
            int minY = start.getY();
            int minZ = start.getZ();
            int maxX = start.getX();
            int maxY = start.getY();
            int maxZ = start.getZ();

            visited.add(start);
            queue.add(start);

            while (!queue.isEmpty()) {
                BlockPos current = queue.poll();
                minX = Math.min(minX, current.getX());
                minY = Math.min(minY, current.getY());
                minZ = Math.min(minZ, current.getZ());
                maxX = Math.max(maxX, current.getX());
                maxY = Math.max(maxY, current.getY());
                maxZ = Math.max(maxZ, current.getZ());

                for (Direction direction : Direction.values()) {
                    BlockPos next = current.offset(direction);
                    BlockHighlight nextHighlight = remaining.get(next);
                    if (nextHighlight == null || visited.contains(next)) {
                        continue;
                    }
                    if (nextHighlight.color() != first.color()
                            || nextHighlight.rule().throughWalls != first.rule().throughWalls) {
                        continue;
                    }
                    visited.add(next);
                    queue.add(next);
                }
            }

            clusters.add(new Cluster(
                    new Box(minX, minY, minZ, maxX + 1.0, maxY + 1.0, maxZ + 1.0),
                    first.color(),
                    first.rule().throughWalls
            ));
        }

        return clusters;
    }

    private record Cluster(Box box, int color, boolean throughWalls) {
    }
}

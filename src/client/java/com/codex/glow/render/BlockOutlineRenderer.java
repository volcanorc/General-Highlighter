package com.codex.glow.render;

import com.codex.glow.config.BlockRenderMode;
import com.codex.glow.highlight.BlockHighlight;
import com.codex.glow.highlight.BlockScanner;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
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
    private static final RenderLayer THROUGH_WALL_LINES = RenderLayer.of(
            "general_highlighter_through_wall_lines",
            VertexFormats.LINES,
            VertexFormat.DrawMode.LINES,
            1536,
            false,
            false,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.LINES_PROGRAM)
                    .lineWidth(RenderPhase.FULL_LINE_WIDTH)
                    .transparency(RenderPhase.TRANSLUCENT_TRANSPARENCY)
                    .depthTest(RenderPhase.ALWAYS_DEPTH_TEST)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .build(false)
    );

    private BlockOutlineRenderer() {
    }

    public static void render(WorldRenderContext context, BlockScanner scanner) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || context.consumers() == null) {
            return;
        }

        MatrixStack matrices = context.matrixStack();
        Vec3d camera = context.camera().getPos();
        List<BlockHighlight> highlights = scanner.getHighlights(client.player);

        matrices.push();
        matrices.translate(-camera.x, -camera.y, -camera.z);

        for (BlockHighlight highlight : highlights) {
            if (highlight.rule().mode == BlockRenderMode.CLUSTER) {
                continue;
            }

            VertexConsumer lines = context.consumers().getBuffer(layerFor(highlight.rule().throughWalls));
            int color = highlight.color();
            float red = ((color >> 16) & 0xFF) / 255.0f;
            float green = ((color >> 8) & 0xFF) / 255.0f;
            float blue = (color & 0xFF) / 255.0f;
            Box box = new Box(highlight.pos()).expand(0.002);
            WorldRenderer.drawBox(matrices, lines, box, red, green, blue, 0.9f);
        }

        for (Cluster cluster : buildClusters(highlights)) {
            VertexConsumer lines = context.consumers().getBuffer(layerFor(cluster.throughWalls));
            drawClusterSurfaces(matrices, lines, cluster);
        }

        RenderSystem.enableDepthTest();
        matrices.pop();
    }

    private static List<Cluster> buildClusters(List<BlockHighlight> highlights) {
        Map<ClusterStyle, Set<BlockPos>> grouped = new HashMap<>();
        for (BlockHighlight highlight : highlights) {
            if (highlight.rule().mode == BlockRenderMode.CLUSTER) {
                grouped.computeIfAbsent(new ClusterStyle(highlight.color(), highlight.rule().throughWalls), ignored -> new HashSet<>())
                        .add(highlight.pos());
            }
        }

        List<Cluster> clusters = new ArrayList<>();
        for (Map.Entry<ClusterStyle, Set<BlockPos>> group : grouped.entrySet()) {
            Set<BlockPos> visited = new HashSet<>();
            Queue<BlockPos> queue = new ArrayDeque<>();
            Set<BlockPos> remaining = group.getValue();

            for (BlockPos start : remaining) {
                if (visited.contains(start)) {
                    continue;
                }

                Set<BlockPos> positions = new HashSet<>();
                visited.add(start);
                queue.add(start);

                while (!queue.isEmpty()) {
                    BlockPos current = queue.poll();
                    positions.add(current);

                    for (Direction direction : Direction.values()) {
                        BlockPos next = current.offset(direction);
                        if (!remaining.contains(next) || visited.contains(next)) {
                            continue;
                        }
                        visited.add(next);
                        queue.add(next);
                    }
                }

                clusters.add(new Cluster(positions, group.getKey().color, group.getKey().throughWalls));
            }
        }

        return clusters;
    }

    private static RenderLayer layerFor(boolean throughWalls) {
        return throughWalls ? THROUGH_WALL_LINES : RenderLayer.getLines();
    }

    private static void drawClusterSurfaces(MatrixStack matrices, VertexConsumer lines, Cluster cluster) {
        for (BlockPos pos : cluster.positions) {
            for (Direction direction : Direction.values()) {
                if (!cluster.positions.contains(pos.offset(direction))) {
                    drawFace(matrices, lines, pos, direction, cluster.color);
                }
            }
        }
    }

    private static void drawFace(MatrixStack matrices, VertexConsumer lines, BlockPos pos, Direction direction, int color) {
        double x1 = pos.getX();
        double y1 = pos.getY();
        double z1 = pos.getZ();
        double x2 = x1 + 1.0;
        double y2 = y1 + 1.0;
        double z2 = z1 + 1.0;

        switch (direction) {
            case DOWN -> drawRect(matrices, lines, x1, y1, z1, x2, y1, z2, color);
            case UP -> drawRect(matrices, lines, x1, y2, z1, x2, y2, z2, color);
            case NORTH -> drawRect(matrices, lines, x1, y1, z1, x2, y2, z1, color);
            case SOUTH -> drawRect(matrices, lines, x1, y1, z2, x2, y2, z2, color);
            case WEST -> drawRect(matrices, lines, x1, y1, z1, x1, y2, z2, color);
            case EAST -> drawRect(matrices, lines, x2, y1, z1, x2, y2, z2, color);
        }
    }

    private static void drawRect(MatrixStack matrices, VertexConsumer lines,
                                 double ax, double ay, double az,
                                 double bx, double by, double bz,
                                 int color) {
        if (ay == by) {
            drawLine(matrices, lines, ax, ay, az, bx, by, az, color);
            drawLine(matrices, lines, bx, by, az, bx, by, bz, color);
            drawLine(matrices, lines, bx, by, bz, ax, ay, bz, color);
            drawLine(matrices, lines, ax, ay, bz, ax, ay, az, color);
        } else if (az == bz) {
            drawLine(matrices, lines, ax, ay, az, bx, ay, bz, color);
            drawLine(matrices, lines, bx, ay, bz, bx, by, bz, color);
            drawLine(matrices, lines, bx, by, bz, ax, by, az, color);
            drawLine(matrices, lines, ax, by, az, ax, ay, az, color);
        } else {
            drawLine(matrices, lines, ax, ay, az, ax, by, az, color);
            drawLine(matrices, lines, ax, by, az, bx, by, bz, color);
            drawLine(matrices, lines, bx, by, bz, bx, ay, bz, color);
            drawLine(matrices, lines, bx, ay, bz, ax, ay, az, color);
        }
    }

    private static void drawLine(MatrixStack matrices, VertexConsumer lines,
                                 double x1, double y1, double z1,
                                 double x2, double y2, double z2,
                                 int color) {
        MatrixStack.Entry entry = matrices.peek();
        int red = (color >> 16) & 0xFF;
        int green = (color >> 8) & 0xFF;
        int blue = color & 0xFF;
        lines.vertex(entry, (float) x1, (float) y1, (float) z1).color(red, green, blue, 242).normal(entry, 0.0f, 1.0f, 0.0f);
        lines.vertex(entry, (float) x2, (float) y2, (float) z2).color(red, green, blue, 242).normal(entry, 0.0f, 1.0f, 0.0f);
    }

    private record ClusterStyle(int color, boolean throughWalls) {
    }

    private record Cluster(Set<BlockPos> positions, int color, boolean throughWalls) {
    }
}

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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

public final class BlockOutlineRenderer {
    private static final int MIN_THROUGH_WALL_ALPHA = 220;
    private static final double THROUGH_WALL_EXPANSION = 0.035;
    private static final double VISIBLE_OVERLAY_EXPANSION = 0.012;
    private static final double VISIBLE_FILL_EXPANSION = 0.003;

    private static final RenderLayer VISIBLE_FILLED = RenderLayer.of(
            "general_highlighter_filled",
            VertexFormats.POSITION_COLOR,
            VertexFormat.DrawMode.QUADS,
            1536,
            false,
            false,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.TRANSLUCENT_TRANSPARENCY)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .build(false)
    );
    private static final RenderLayer THROUGH_WALL_FILLED = RenderLayer.of(
            "general_highlighter_through_wall_filled",
            VertexFormats.POSITION_COLOR,
            VertexFormat.DrawMode.QUADS,
            1536,
            false,
            false,
            RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.TRANSLUCENT_TRANSPARENCY)
                    .depthTest(RenderPhase.ALWAYS_DEPTH_TEST)
                    .cull(RenderPhase.DISABLE_CULLING)
                    .writeMaskState(RenderPhase.COLOR_MASK)
                    .build(false)
    );
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
            if (isClusterMode(highlight.rule().mode)) {
                continue;
            }

            int color = highlight.color();
            if (highlight.rule().throughWalls) {
                VertexConsumer filled = context.consumers().getBuffer(THROUGH_WALL_FILLED);
                drawFilledBlock(matrices, filled, highlight.pos(), color, throughWallAlpha(highlight.rule().fillAlpha), THROUGH_WALL_EXPANSION);

                VertexConsumer lines = context.consumers().getBuffer(THROUGH_WALL_LINES);
                drawOutline(matrices, lines, highlight.pos(), color, 1.0f);
            } else if (highlight.rule().mode == BlockRenderMode.OVERLAY) {
                VertexConsumer filled = context.consumers().getBuffer(filledLayerFor(highlight.rule().throughWalls));
                drawFilledBlock(matrices, filled, highlight.pos(), color, Math.max(highlight.rule().fillAlpha, 160), VISIBLE_OVERLAY_EXPANSION);
            } else if (highlight.rule().mode == BlockRenderMode.FILLED) {
                VertexConsumer filled = context.consumers().getBuffer(filledLayerFor(highlight.rule().throughWalls));
                drawFilledBlock(matrices, filled, highlight.pos(), color, highlight.rule().fillAlpha, VISIBLE_FILL_EXPANSION);
            } else {
                VertexConsumer lines = context.consumers().getBuffer(lineLayerFor(highlight.rule().throughWalls));
                drawOutline(matrices, lines, highlight.pos(), color, 0.9f);
            }
        }

        Map<ClusterStyle, Integer> renderedClusterCounts = new HashMap<>();
        for (Cluster cluster : buildClusters(highlights, client.player.getPos())) {
            int renderedForStyle = renderedClusterCounts.getOrDefault(cluster.style, 0);
            if (renderedForStyle >= cluster.maxClusters) {
                continue;
            }
            renderedClusterCounts.put(cluster.style, renderedForStyle + 1);

            if (cluster.throughWalls) {
                VertexConsumer filled = context.consumers().getBuffer(THROUGH_WALL_FILLED);
                drawClusterFilledSurfaces(matrices, filled, cluster.withAlpha(throughWallAlpha(cluster.alpha)));

                VertexConsumer lines = context.consumers().getBuffer(THROUGH_WALL_LINES);
                drawClusterSurfaces(matrices, lines, cluster);
            } else if (cluster.filled) {
                VertexConsumer filled = context.consumers().getBuffer(filledLayerFor(false));
                drawClusterFilledSurfaces(matrices, filled, cluster);
            } else {
                VertexConsumer lines = context.consumers().getBuffer(lineLayerFor(false));
                drawClusterSurfaces(matrices, lines, cluster);
            }
        }

        RenderSystem.enableDepthTest();
        matrices.pop();
    }

    private static List<Cluster> buildClusters(List<BlockHighlight> highlights, Vec3d playerPos) {
        Map<ClusterStyle, ClusterGroup> grouped = new HashMap<>();
        for (BlockHighlight highlight : highlights) {
            if (isClusterMode(highlight.rule().mode)) {
                boolean filled = highlight.rule().mode == BlockRenderMode.FILLED_CLUSTER;
                ClusterStyle style = new ClusterStyle(highlight.color(), highlight.rule().throughWalls, filled, highlight.rule().fillAlpha);
                ClusterGroup group = grouped.computeIfAbsent(style, ignored -> new ClusterGroup());
                group.positions.add(highlight.pos());
                group.maxClusters = Math.min(group.maxClusters, highlight.rule().maxClusters);
            }
        }

        List<Cluster> clusters = new ArrayList<>();
        for (Map.Entry<ClusterStyle, ClusterGroup> group : grouped.entrySet()) {
            Set<BlockPos> visited = new HashSet<>();
            Queue<BlockPos> queue = new ArrayDeque<>();
            Set<BlockPos> remaining = group.getValue().positions;

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

                ClusterStyle style = group.getKey();
                clusters.add(new Cluster(positions, style.color, style.throughWalls, style.filled, style.alpha, style, group.getValue().maxClusters,
                        nearestDistanceSquared(positions, playerPos)));
            }
        }

        clusters.sort(Comparator.comparingDouble(Cluster::nearestDistanceSquared));
        return clusters;
    }

    private static boolean isClusterMode(BlockRenderMode mode) {
        return mode == BlockRenderMode.CLUSTER || mode == BlockRenderMode.FILLED_CLUSTER;
    }

    private static RenderLayer lineLayerFor(boolean throughWalls) {
        return throughWalls ? THROUGH_WALL_LINES : RenderLayer.getLines();
    }

    private static RenderLayer filledLayerFor(boolean throughWalls) {
        return throughWalls ? THROUGH_WALL_FILLED : VISIBLE_FILLED;
    }

    private static int throughWallAlpha(int alpha) {
        return Math.max(alpha, MIN_THROUGH_WALL_ALPHA);
    }

    private static void drawOutline(MatrixStack matrices, VertexConsumer lines, BlockPos pos, int color, float alpha) {
        float red = ((color >> 16) & 0xFF) / 255.0f;
        float green = ((color >> 8) & 0xFF) / 255.0f;
        float blue = (color & 0xFF) / 255.0f;
        Box box = new Box(pos).expand(0.01);
        WorldRenderer.drawBox(matrices, lines, box, red, green, blue, alpha);
    }

    private static void drawClusterSurfaces(MatrixStack matrices, VertexConsumer lines, Cluster cluster) {
        drawMergedClusterSurfaces(matrices, lines, null, cluster, false);
    }

    private static void drawClusterFilledSurfaces(MatrixStack matrices, VertexConsumer filled, Cluster cluster) {
        drawMergedClusterSurfaces(matrices, null, filled, cluster, true);
    }

    private static void drawMergedClusterSurfaces(MatrixStack matrices, VertexConsumer lines, VertexConsumer filled, Cluster cluster, boolean fill) {
        Map<FacePlane, Set<GridCell>> faces = new HashMap<>();
        for (BlockPos pos : cluster.positions) {
            for (Direction direction : Direction.values()) {
                if (!cluster.positions.contains(pos.offset(direction))) {
                    FacePlane plane = facePlane(pos, direction);
                    faces.computeIfAbsent(plane, ignored -> new HashSet<>()).add(faceCell(pos, direction));
                }
            }
        }

        for (Map.Entry<FacePlane, Set<GridCell>> entry : faces.entrySet()) {
            drawMergedPlane(matrices, lines, filled, entry.getKey(), entry.getValue(), cluster.color, cluster.alpha, fill);
        }
    }

    private static void drawMergedPlane(MatrixStack matrices, VertexConsumer lines, VertexConsumer filled,
                                        FacePlane plane, Set<GridCell> cells, int color, int alpha, boolean fill) {
        Set<GridCell> remaining = new HashSet<>(cells);
        while (!remaining.isEmpty()) {
            GridCell start = remaining.stream()
                    .min(Comparator.comparingInt(GridCell::b).thenComparingInt(GridCell::a))
                    .orElseThrow();

            int width = 1;
            while (remaining.contains(new GridCell(start.a + width, start.b))) {
                width++;
            }

            int height = 1;
            boolean canGrow = true;
            while (canGrow) {
                for (int offset = 0; offset < width; offset++) {
                    if (!remaining.contains(new GridCell(start.a + offset, start.b + height))) {
                        canGrow = false;
                        break;
                    }
                }
                if (canGrow) {
                    height++;
                }
            }

            for (int db = 0; db < height; db++) {
                for (int da = 0; da < width; da++) {
                    remaining.remove(new GridCell(start.a + da, start.b + db));
                }
            }

            if (fill) {
                drawMergedFilledFace(matrices, filled, plane, start.a, start.b, width, height, color, alpha);
            } else {
                drawMergedFace(matrices, lines, plane, start.a, start.b, width, height, color);
            }
        }
    }

    private static void drawFilledBlock(MatrixStack matrices, VertexConsumer filled, BlockPos pos, int color, int alpha, double expansion) {
        for (Direction direction : Direction.values()) {
            drawFilledFace(matrices, filled, pos, direction, color, alpha, expansion);
        }
    }

    private static FacePlane facePlane(BlockPos pos, Direction direction) {
        return switch (direction) {
            case DOWN -> new FacePlane(direction, pos.getY());
            case UP -> new FacePlane(direction, pos.getY() + 1);
            case NORTH -> new FacePlane(direction, pos.getZ());
            case SOUTH -> new FacePlane(direction, pos.getZ() + 1);
            case WEST -> new FacePlane(direction, pos.getX());
            case EAST -> new FacePlane(direction, pos.getX() + 1);
        };
    }

    private static GridCell faceCell(BlockPos pos, Direction direction) {
        return switch (direction) {
            case DOWN, UP -> new GridCell(pos.getX(), pos.getZ());
            case NORTH, SOUTH -> new GridCell(pos.getX(), pos.getY());
            case WEST, EAST -> new GridCell(pos.getZ(), pos.getY());
        };
    }

    private static void drawMergedFace(MatrixStack matrices, VertexConsumer lines, FacePlane plane,
                                       int a, int b, int width, int height, int color) {
        switch (plane.direction) {
            case DOWN, UP -> drawRect(matrices, lines, a, plane.plane, b, a + width, plane.plane, b + height, color);
            case NORTH, SOUTH -> drawRect(matrices, lines, a, b, plane.plane, a + width, b + height, plane.plane, color);
            case WEST, EAST -> drawRect(matrices, lines, plane.plane, b, a, plane.plane, b + height, a + width, color);
        }
    }

    private static void drawMergedFilledFace(MatrixStack matrices, VertexConsumer filled, FacePlane plane,
                                             int a, int b, int width, int height, int color, int alpha) {
        switch (plane.direction) {
            case DOWN -> drawQuad(filled, matrices, a, plane.plane, b, a, plane.plane, b + height,
                    a + width, plane.plane, b + height, a + width, plane.plane, b, color, alpha);
            case UP -> drawQuad(filled, matrices, a, plane.plane, b, a + width, plane.plane, b,
                    a + width, plane.plane, b + height, a, plane.plane, b + height, color, alpha);
            case NORTH -> drawQuad(filled, matrices, a, b, plane.plane, a + width, b, plane.plane,
                    a + width, b + height, plane.plane, a, b + height, plane.plane, color, alpha);
            case SOUTH -> drawQuad(filled, matrices, a, b, plane.plane, a, b + height, plane.plane,
                    a + width, b + height, plane.plane, a + width, b, plane.plane, color, alpha);
            case WEST -> drawQuad(filled, matrices, plane.plane, b, a, plane.plane, b + height, a,
                    plane.plane, b + height, a + width, plane.plane, b, a + width, color, alpha);
            case EAST -> drawQuad(filled, matrices, plane.plane, b, a, plane.plane, b, a + width,
                    plane.plane, b + height, a + width, plane.plane, b + height, a, color, alpha);
        }
    }

    private static double nearestDistanceSquared(Set<BlockPos> positions, Vec3d playerPos) {
        double nearest = Double.MAX_VALUE;
        for (BlockPos pos : positions) {
            double dx = pos.getX() + 0.5 - playerPos.x;
            double dy = pos.getY() + 0.5 - playerPos.y;
            double dz = pos.getZ() + 0.5 - playerPos.z;
            nearest = Math.min(nearest, dx * dx + dy * dy + dz * dz);
        }
        return nearest;
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

    private static void drawFilledFace(MatrixStack matrices, VertexConsumer filled, BlockPos pos, Direction direction, int color, int alpha, double expansion) {
        double x1 = pos.getX() - expansion;
        double y1 = pos.getY() - expansion;
        double z1 = pos.getZ() - expansion;
        double x2 = pos.getX() + 1.0 + expansion;
        double y2 = pos.getY() + 1.0 + expansion;
        double z2 = pos.getZ() + 1.0 + expansion;

        switch (direction) {
            case DOWN -> drawQuad(filled, matrices, x1, y1, z1, x1, y1, z2, x2, y1, z2, x2, y1, z1, color, alpha);
            case UP -> drawQuad(filled, matrices, x1, y2, z1, x2, y2, z1, x2, y2, z2, x1, y2, z2, color, alpha);
            case NORTH -> drawQuad(filled, matrices, x1, y1, z1, x2, y1, z1, x2, y2, z1, x1, y2, z1, color, alpha);
            case SOUTH -> drawQuad(filled, matrices, x1, y1, z2, x1, y2, z2, x2, y2, z2, x2, y1, z2, color, alpha);
            case WEST -> drawQuad(filled, matrices, x1, y1, z1, x1, y2, z1, x1, y2, z2, x1, y1, z2, color, alpha);
            case EAST -> drawQuad(filled, matrices, x2, y1, z1, x2, y1, z2, x2, y2, z2, x2, y2, z1, color, alpha);
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

    private static void drawQuad(VertexConsumer filled, MatrixStack matrices,
                                 double x1, double y1, double z1,
                                 double x2, double y2, double z2,
                                 double x3, double y3, double z3,
                                 double x4, double y4, double z4,
                                 int color, int alpha) {
        MatrixStack.Entry entry = matrices.peek();
        int red = (color >> 16) & 0xFF;
        int green = (color >> 8) & 0xFF;
        int blue = color & 0xFF;
        filled.vertex(entry, (float) x1, (float) y1, (float) z1).color(red, green, blue, alpha);
        filled.vertex(entry, (float) x2, (float) y2, (float) z2).color(red, green, blue, alpha);
        filled.vertex(entry, (float) x3, (float) y3, (float) z3).color(red, green, blue, alpha);
        filled.vertex(entry, (float) x4, (float) y4, (float) z4).color(red, green, blue, alpha);
    }

    private record ClusterStyle(int color, boolean throughWalls, boolean filled, int alpha) {
    }

    private static final class ClusterGroup {
        private final Set<BlockPos> positions = new HashSet<>();
        private int maxClusters = Integer.MAX_VALUE;
    }

    private record FacePlane(Direction direction, int plane) {
    }

    private record GridCell(int a, int b) {
    }

    private record Cluster(Set<BlockPos> positions, int color, boolean throughWalls, boolean filled, int alpha,
                           ClusterStyle style, int maxClusters, double nearestDistanceSquared) {
        private Cluster withAlpha(int nextAlpha) {
            return new Cluster(positions, color, throughWalls, filled, nextAlpha, style, maxClusters, nearestDistanceSquared);
        }
    }
}

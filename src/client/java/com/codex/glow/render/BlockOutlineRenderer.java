package com.codex.glow.render;

import com.codex.glow.config.BlockRenderMode;
import com.codex.glow.highlight.BlockHighlight;
import com.codex.glow.highlight.BlockScanner;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
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
import java.util.function.Consumer;

public final class BlockOutlineRenderer {
    private static final int MIN_THROUGH_WALL_ALPHA = 220;
    private static final double THROUGH_WALL_EXPANSION = 0.035;
    private static final double VISIBLE_OVERLAY_EXPANSION = 0.012;
    private static final double VISIBLE_FILL_EXPANSION = 0.003;
    private static final double MARKER_RADIUS = 0.22;
    private static final double MARKER_TOP_OFFSET = 1.75;

    private BlockOutlineRenderer() {
    }

    public static void render(WorldRenderContext context, BlockScanner scanner) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            return;
        }

        MatrixStack matrices = context.matrixStack();
        Vec3d camera = context.camera().getPos();
        List<BlockHighlight> highlights = scanner.getHighlights(client.player);
        List<Cluster> clusters = buildClusters(highlights, client.player.getPos());

        matrices.push();
        matrices.translate(-camera.x, -camera.y, -camera.z);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();

        renderVisibleHighlights(matrices, highlights, clusters);
        renderThroughWallHighlights(matrices, highlights, clusters);

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        matrices.pop();
    }

    private static void renderVisibleHighlights(MatrixStack matrices, List<BlockHighlight> highlights, List<Cluster> clusters) {
        RenderSystem.enableDepthTest();

        drawQuads(filled -> {
            for (BlockHighlight highlight : highlights) {
                if (highlight.rule().throughWalls || isClusterMode(highlight.rule().mode)) {
                    continue;
                }

                int color = highlight.color();
                if (highlight.rule().mode == BlockRenderMode.OVERLAY) {
                    drawFilledBlock(matrices, filled, highlight.pos(), color, Math.max(highlight.rule().fillAlpha, 160), VISIBLE_OVERLAY_EXPANSION);
                } else if (highlight.rule().mode == BlockRenderMode.FILLED) {
                    drawFilledBlock(matrices, filled, highlight.pos(), color, highlight.rule().fillAlpha, VISIBLE_FILL_EXPANSION);
                }
            }

            for (Cluster cluster : limitedClusters(clusters, false)) {
                if (!cluster.throughWalls && cluster.filled) {
                    drawClusterFilledSurfaces(matrices, filled, cluster);
                }
            }
        });

        drawLines(lines -> {
            for (BlockHighlight highlight : highlights) {
                if (highlight.rule().throughWalls || isClusterMode(highlight.rule().mode)
                        || highlight.rule().mode == BlockRenderMode.OVERLAY || highlight.rule().mode == BlockRenderMode.FILLED) {
                    continue;
                }
                drawOutline(matrices, lines, highlight.pos(), highlight.color(), 242);
            }

            for (Cluster cluster : limitedClusters(clusters, false)) {
                if (!cluster.throughWalls && !cluster.filled) {
                    drawClusterSurfaces(matrices, lines, cluster);
                }
            }
        });
    }

    private static void renderThroughWallHighlights(MatrixStack matrices, List<BlockHighlight> highlights, List<Cluster> clusters) {
        RenderSystem.disableDepthTest();
        RenderSystem.lineWidth(2.0f);

        drawQuads(filled -> {
            for (BlockHighlight highlight : highlights) {
                if (!highlight.rule().throughWalls || isClusterMode(highlight.rule().mode)) {
                    continue;
                }
                drawFilledBlock(matrices, filled, highlight.pos(), highlight.color(), throughWallAlpha(highlight.rule().fillAlpha), THROUGH_WALL_EXPANSION);
            }

            for (Cluster cluster : limitedClusters(clusters, true)) {
                drawClusterFilledSurfaces(matrices, filled, cluster.withAlpha(throughWallAlpha(cluster.alpha)));
            }
        });

        drawLines(lines -> {
            for (BlockHighlight highlight : highlights) {
                if (!highlight.rule().throughWalls || isClusterMode(highlight.rule().mode)) {
                    continue;
                }
                drawOutline(matrices, lines, highlight.pos(), highlight.color(), 255);
                drawMarker(matrices, lines, highlight.pos(), highlight.color());
            }

            for (Cluster cluster : limitedClusters(clusters, true)) {
                drawClusterSurfaces(matrices, lines, cluster);
            }
        });

        RenderSystem.lineWidth(1.0f);
    }

    private static void drawQuads(Consumer<VertexConsumer> draw) {
        drawImmediate(VertexFormat.DrawMode.QUADS, draw);
    }

    private static void drawLines(Consumer<VertexConsumer> draw) {
        drawImmediate(VertexFormat.DrawMode.DEBUG_LINES, draw);
    }

    private static void drawImmediate(VertexFormat.DrawMode mode, Consumer<VertexConsumer> draw) {
        BufferBuilder builder = Tessellator.getInstance().begin(mode, VertexFormats.POSITION_COLOR);
        draw.accept(builder);
        BuiltBuffer buffer = builder.endNullable();
        if (buffer != null) {
            BufferRenderer.drawWithGlobalProgram(buffer);
        }
    }

    private static void drawMarker(MatrixStack matrices, VertexConsumer lines, BlockPos pos, int color) {
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 0.5;
        double z = pos.getZ() + 0.5;
        double topY = pos.getY() + MARKER_TOP_OFFSET;

        drawLine(matrices, lines, x, y, z, x, topY, z, color, 255);
        drawLine(matrices, lines, x - MARKER_RADIUS, topY, z, x + MARKER_RADIUS, topY, z, color, 255);
        drawLine(matrices, lines, x, topY, z - MARKER_RADIUS, x, topY, z + MARKER_RADIUS, color, 255);
        drawLine(matrices, lines, x - MARKER_RADIUS, topY - MARKER_RADIUS, z, x + MARKER_RADIUS, topY + MARKER_RADIUS, z, color, 255);
        drawLine(matrices, lines, x + MARKER_RADIUS, topY - MARKER_RADIUS, z, x - MARKER_RADIUS, topY + MARKER_RADIUS, z, color, 255);
    }

    private static List<Cluster> limitedClusters(List<Cluster> clusters, boolean throughWalls) {
        List<Cluster> limited = new ArrayList<>();
        Map<ClusterStyle, Integer> renderedClusterCounts = new HashMap<>();
        for (Cluster cluster : clusters) {
            if (cluster.throughWalls != throughWalls) {
                continue;
            }

            int renderedForStyle = renderedClusterCounts.getOrDefault(cluster.style, 0);
            if (renderedForStyle >= cluster.maxClusters) {
                continue;
            }

            renderedClusterCounts.put(cluster.style, renderedForStyle + 1);
            limited.add(cluster);
        }
        return limited;
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

    private static int throughWallAlpha(int alpha) {
        return Math.max(alpha, MIN_THROUGH_WALL_ALPHA);
    }

    private static void drawOutline(MatrixStack matrices, VertexConsumer lines, BlockPos pos, int color, int alpha) {
        double x1 = pos.getX() - 0.01;
        double y1 = pos.getY() - 0.01;
        double z1 = pos.getZ() - 0.01;
        double x2 = pos.getX() + 1.01;
        double y2 = pos.getY() + 1.01;
        double z2 = pos.getZ() + 1.01;

        drawRect(matrices, lines, x1, y1, z1, x2, y1, z2, color, alpha);
        drawRect(matrices, lines, x1, y2, z1, x2, y2, z2, color, alpha);
        drawLine(matrices, lines, x1, y1, z1, x1, y2, z1, color, alpha);
        drawLine(matrices, lines, x2, y1, z1, x2, y2, z1, color, alpha);
        drawLine(matrices, lines, x1, y1, z2, x1, y2, z2, color, alpha);
        drawLine(matrices, lines, x2, y1, z2, x2, y2, z2, color, alpha);
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
        drawRect(matrices, lines, ax, ay, az, bx, by, bz, color, 242);
    }

    private static void drawRect(MatrixStack matrices, VertexConsumer lines,
                                 double ax, double ay, double az,
                                 double bx, double by, double bz,
                                 int color, int alpha) {
        if (ay == by) {
            drawLine(matrices, lines, ax, ay, az, bx, by, az, color, alpha);
            drawLine(matrices, lines, bx, by, az, bx, by, bz, color, alpha);
            drawLine(matrices, lines, bx, by, bz, ax, ay, bz, color, alpha);
            drawLine(matrices, lines, ax, ay, bz, ax, ay, az, color, alpha);
        } else if (az == bz) {
            drawLine(matrices, lines, ax, ay, az, bx, ay, bz, color, alpha);
            drawLine(matrices, lines, bx, ay, bz, bx, by, bz, color, alpha);
            drawLine(matrices, lines, bx, by, bz, ax, by, az, color, alpha);
            drawLine(matrices, lines, ax, by, az, ax, ay, az, color, alpha);
        } else {
            drawLine(matrices, lines, ax, ay, az, ax, by, az, color, alpha);
            drawLine(matrices, lines, ax, by, az, bx, by, bz, color, alpha);
            drawLine(matrices, lines, bx, by, bz, bx, ay, bz, color, alpha);
            drawLine(matrices, lines, bx, ay, bz, ax, ay, az, color, alpha);
        }
    }

    private static void drawLine(MatrixStack matrices, VertexConsumer lines,
                                 double x1, double y1, double z1,
                                 double x2, double y2, double z2,
                                 int color) {
        drawLine(matrices, lines, x1, y1, z1, x2, y2, z2, color, 242);
    }

    private static void drawLine(MatrixStack matrices, VertexConsumer lines,
                                 double x1, double y1, double z1,
                                 double x2, double y2, double z2,
                                 int color, int alpha) {
        MatrixStack.Entry entry = matrices.peek();
        int red = (color >> 16) & 0xFF;
        int green = (color >> 8) & 0xFF;
        int blue = color & 0xFF;
        lines.vertex(entry, (float) x1, (float) y1, (float) z1).color(red, green, blue, alpha);
        lines.vertex(entry, (float) x2, (float) y2, (float) z2).color(red, green, blue, alpha);
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

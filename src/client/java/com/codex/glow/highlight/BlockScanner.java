package com.codex.glow.highlight;

import com.codex.glow.config.BlockRule;
import com.codex.glow.config.HighlightConfig;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.Chunk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

public final class BlockScanner {
    private static final int CHUNKS_PER_TICK = 1;

    private final HighlightConfig config;
    private final Map<Long, List<BlockHighlight>> highlightsByChunk = new HashMap<>();
    private final Queue<ChunkPos> scanQueue = new ArrayDeque<>();

    private ChunkPos lastPlayerChunk;
    private int lastRange = -1;

    public BlockScanner(HighlightConfig config) {
        this.config = config;
    }

    public void tick(MinecraftClient client) {
        ClientWorld world = client.world;
        ClientPlayerEntity player = client.player;
        if (world == null || player == null) {
            clear();
            return;
        }

        if (!config.hasEnabledBlocks()) {
            clear();
            return;
        }

        ChunkPos playerChunk = player.getChunkPos();
        int range = config.maxEnabledBlockRange();
        if (!playerChunk.equals(lastPlayerChunk) || range != lastRange || scanQueue.isEmpty()) {
            rebuildQueue(playerChunk, range);
            pruneDistantChunks(playerChunk, range);
        }

        for (int i = 0; i < CHUNKS_PER_TICK && !scanQueue.isEmpty(); i++) {
            scanChunk(world, player, scanQueue.poll());
        }
    }

    public void clear() {
        highlightsByChunk.clear();
        scanQueue.clear();
        lastPlayerChunk = null;
        lastRange = -1;
    }

    public void requestRescan() {
        lastPlayerChunk = null;
        scanQueue.clear();
    }

    public void onChunkLoaded(ChunkPos pos) {
        if (config.hasEnabledBlocks()) {
            scanQueue.add(pos);
        }
    }

    public void onChunkUnloaded(ChunkPos pos) {
        highlightsByChunk.remove(pos.toLong());
        scanQueue.removeIf(queued -> queued.equals(pos));
    }

    public List<BlockHighlight> getHighlights(ClientPlayerEntity player) {
        if (player == null) {
            return List.of();
        }

        int max = config.maxBlockHighlights();
        return highlightsByChunk.values().stream()
                .flatMap(List::stream)
                .filter(highlight -> player.squaredDistanceTo(
                        highlight.pos().getX() + 0.5,
                        highlight.pos().getY() + 0.5,
                        highlight.pos().getZ() + 0.5) <= (double) highlight.rule().range * highlight.rule().range)
                .sorted(Comparator.comparingDouble(highlight -> player.squaredDistanceTo(
                        highlight.pos().getX() + 0.5,
                        highlight.pos().getY() + 0.5,
                        highlight.pos().getZ() + 0.5)))
                .limit(max)
                .toList();
    }

    private void rebuildQueue(ChunkPos center, int range) {
        scanQueue.clear();
        int chunkRadius = Math.max(1, (int) Math.ceil(range / 16.0));
        for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                scanQueue.add(new ChunkPos(center.x + dx, center.z + dz));
            }
        }
        lastPlayerChunk = center;
        lastRange = range;
    }

    private void pruneDistantChunks(ChunkPos center, int range) {
        int chunkRadius = Math.max(1, (int) Math.ceil(range / 16.0)) + 1;
        highlightsByChunk.keySet().removeIf(key -> {
            ChunkPos chunk = new ChunkPos(key);
            return Math.abs(chunk.x - center.x) > chunkRadius || Math.abs(chunk.z - center.z) > chunkRadius;
        });
    }

    private void scanChunk(ClientWorld world, ClientPlayerEntity player, ChunkPos chunkPos) {
        Chunk chunk = world.getChunk(chunkPos.x, chunkPos.z, ChunkStatus.FULL, false);
        long key = chunkPos.toLong();
        if (chunk == null) {
            highlightsByChunk.remove(key);
            return;
        }

        List<BlockHighlight> found = new ArrayList<>();
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        int minY = world.getBottomY();
        int maxY = world.getTopY();

        for (int y = minY; y < maxY; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    mutable.set(chunkPos.getStartX() + x, y, chunkPos.getStartZ() + z);
                    BlockState state = chunk.getBlockState(mutable);
                    Identifier id = Registries.BLOCK.getId(state.getBlock());
                    BlockRule rule = config.getEnabledBlockRule(id);
                    if (rule == null) {
                        continue;
                    }

                    double rangeSq = rule.range * rule.range;
                    if (player.squaredDistanceTo(mutable.getX() + 0.5, mutable.getY() + 0.5, mutable.getZ() + 0.5) > rangeSq) {
                        continue;
                    }

                    found.add(new BlockHighlight(mutable.toImmutable(), rule, HighlightConfig.parseColor(rule.color)));
                }
            }
        }

        if (found.isEmpty()) {
            highlightsByChunk.remove(key);
        } else {
            highlightsByChunk.put(key, found);
        }
    }
}

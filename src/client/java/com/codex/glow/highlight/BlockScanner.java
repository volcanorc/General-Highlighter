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

public final class BlockScanner {
    private static final int SECTION_HEIGHT = 16;
    private static final int SECTIONS_PER_TICK = 4;

    private final HighlightConfig config;
    private final Map<SectionKey, List<BlockHighlight>> highlightsBySection = new HashMap<>();
    private final ArrayDeque<ScanSection> scanQueue = new ArrayDeque<>();

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
        int range = effectiveScanRange(client);
        if (!playerChunk.equals(lastPlayerChunk) || range != lastRange || scanQueue.isEmpty()) {
            rebuildQueue(world, playerChunk, range);
            pruneDistantSections(playerChunk, range);
        }

        for (int i = 0; i < SECTIONS_PER_TICK && !scanQueue.isEmpty(); i++) {
            scanSection(world, player, scanQueue.poll(), range);
        }
    }

    public void clear() {
        highlightsBySection.clear();
        scanQueue.clear();
        lastPlayerChunk = null;
        lastRange = -1;
    }

    public void requestRescan() {
        lastPlayerChunk = null;
        highlightsBySection.clear();
        scanQueue.clear();
    }

    public void onChunkLoaded(ClientWorld world, ChunkPos pos) {
        if (config.hasEnabledBlocks()) {
            enqueueChunkSections(world, pos);
        }
    }

    public void onChunkUnloaded(ChunkPos pos) {
        highlightsBySection.keySet().removeIf(key -> key.chunkX == pos.x && key.chunkZ == pos.z);
        scanQueue.removeIf(queued -> queued.chunkX == pos.x && queued.chunkZ == pos.z);
    }

    public void onBlockUpdated(BlockPos pos) {
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        int sectionY = Math.floorDiv(pos.getY(), SECTION_HEIGHT) * SECTION_HEIGHT;
        SectionKey key = new SectionKey(chunkX, chunkZ, sectionY);
        highlightsBySection.remove(key);
        scanQueue.removeIf(queued -> queued.matches(key));
        scanQueue.addFirst(new ScanSection(chunkX, chunkZ, sectionY));
    }

    public List<BlockHighlight> getHighlights(ClientPlayerEntity player) {
        if (player == null) {
            return List.of();
        }

        int max = config.maxBlockHighlights();
        return highlightsBySection.values().stream()
                .flatMap(List::stream)
                .filter(highlight -> {
                    int range = effectiveRuleRange(highlight.rule());
                    return player.squaredDistanceTo(
                        highlight.pos().getX() + 0.5,
                        highlight.pos().getY() + 0.5,
                        highlight.pos().getZ() + 0.5) <= (double) range * range;
                })
                .sorted(Comparator.comparingDouble(highlight -> player.squaredDistanceTo(
                        highlight.pos().getX() + 0.5,
                        highlight.pos().getY() + 0.5,
                        highlight.pos().getZ() + 0.5)))
                .limit(max)
                .toList();
    }

    private void rebuildQueue(ClientWorld world, ChunkPos center, int range) {
        scanQueue.clear();
        int chunkRadius = Math.max(1, (int) Math.ceil(range / 16.0));
        for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                enqueueChunkSections(world, new ChunkPos(center.x + dx, center.z + dz));
            }
        }
        lastPlayerChunk = center;
        lastRange = range;
    }

    private void pruneDistantSections(ChunkPos center, int range) {
        int chunkRadius = Math.max(1, (int) Math.ceil(range / 16.0)) + 1;
        highlightsBySection.keySet().removeIf(key ->
                Math.abs(key.chunkX - center.x) > chunkRadius || Math.abs(key.chunkZ - center.z) > chunkRadius);
    }

    private void scanSection(ClientWorld world, ClientPlayerEntity player, ScanSection section, int scanRange) {
        Chunk chunk = world.getChunk(section.chunkX, section.chunkZ, ChunkStatus.FULL, false);
        SectionKey key = section.key();
        if (chunk == null) {
            highlightsBySection.remove(key);
            return;
        }

        List<BlockHighlight> found = new ArrayList<>();
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        int minY = Math.max(world.getBottomY(), section.startY);
        int maxY = Math.min(world.getTopY(), section.startY + SECTION_HEIGHT);

        for (int y = minY; y < maxY; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    mutable.set((section.chunkX << 4) + x, y, (section.chunkZ << 4) + z);
                    BlockState state = chunk.getBlockState(mutable);
                    Identifier id = Registries.BLOCK.getId(state.getBlock());
                    BlockRule rule = config.getEnabledBlockRule(id);
                    if (rule == null) {
                        continue;
                    }

                    int ruleRange = config.useLoadedChunkRange ? scanRange : rule.range;
                    double rangeSq = (double) ruleRange * ruleRange;
                    if (player.squaredDistanceTo(mutable.getX() + 0.5, mutable.getY() + 0.5, mutable.getZ() + 0.5) > rangeSq) {
                        continue;
                    }

                    found.add(new BlockHighlight(mutable.toImmutable(), rule, HighlightConfig.parseColor(rule.color)));
                }
            }
        }

        if (found.isEmpty()) {
            highlightsBySection.remove(key);
        } else {
            highlightsBySection.put(key, found);
        }
    }

    private void enqueueChunkSections(ClientWorld world, ChunkPos chunkPos) {
        int minY = Math.floorDiv(world.getBottomY(), SECTION_HEIGHT) * SECTION_HEIGHT;
        int maxY = world.getTopY();
        for (int y = minY; y < maxY; y += SECTION_HEIGHT) {
            scanQueue.add(new ScanSection(chunkPos.x, chunkPos.z, y));
        }
    }

    private int effectiveScanRange(MinecraftClient client) {
        if (!config.useLoadedChunkRange) {
            return config.maxEnabledBlockRange();
        }
        return Math.max(16, client.options.getViewDistance().getValue() * 16);
    }

    private int effectiveRuleRange(BlockRule rule) {
        return config.useLoadedChunkRange && lastRange > 0 ? lastRange : rule.range;
    }

    private record SectionKey(int chunkX, int chunkZ, int startY) {
    }

    private record ScanSection(int chunkX, int chunkZ, int startY) {
        private SectionKey key() {
            return new SectionKey(chunkX, chunkZ, startY);
        }

        private boolean matches(SectionKey key) {
            return chunkX == key.chunkX && chunkZ == key.chunkZ && startY == key.startY;
        }
    }
}

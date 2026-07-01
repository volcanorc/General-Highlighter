package com.codex.glow.highlight;

import com.codex.glow.config.BlockRule;
import com.codex.glow.config.HighlightConfig;
import com.codex.glow.smart.SmartHighlightManager;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BlockScanner {
    private static final int SECTION_HEIGHT = 16;
    private static final int SECTIONS_PER_TICK = 4;
    private static final int PRIORITY_SECTIONS_PER_TICK = 64;
    private static final int IMMEDIATE_SECTION_LIMIT = 24;
    private static final int IMMEDIATE_BLOCK_LIMIT = 65_536;

    private final HighlightConfig config;
    private final SmartHighlightManager smartHighlightManager;
    private final Map<SectionKey, List<BlockHighlight>> highlightsBySection = new HashMap<>();
    private final ArrayDeque<ScanSection> scanQueue = new ArrayDeque<>();

    private ChunkPos lastPlayerChunk;
    private int lastRange = -1;
    private int prioritySectionsRemaining;
    private boolean immediateScanPending;
    private String statusText = "Ready";
    private int statusHoldTicks;

    public BlockScanner(HighlightConfig config, SmartHighlightManager smartHighlightManager) {
        this.config = config;
        this.smartHighlightManager = smartHighlightManager;
    }

    public void tick(MinecraftClient client) {
        ClientWorld world = client.world;
        ClientPlayerEntity player = client.player;
        if (world == null || player == null) {
            clear();
            return;
        }

        if (!config.hasEnabledBlocks() && !smartHighlightManager.hasActiveBlockTargets()) {
            clear();
            return;
        }

        ChunkPos playerChunk = player.getChunkPos();
        int range = effectiveScanRange(client);
        if (!playerChunk.equals(lastPlayerChunk) || range != lastRange) {
            rebuildQueue(world, playerChunk, player.getBlockY(), range);
            pruneDistantSections(playerChunk, range);
        }

        if (immediateScanPending && config.fastScanOnToggle) {
            runImmediateNearbyScan(world, player, range);
            immediateScanPending = false;
            statusText = "Immediate scan complete";
            statusHoldTicks = 40;
        }

        int budget = prioritySectionsRemaining > 0 ? Math.max(SECTIONS_PER_TICK, PRIORITY_SECTIONS_PER_TICK) : SECTIONS_PER_TICK;
        for (int i = 0; i < budget && !scanQueue.isEmpty(); i++) {
            scanSection(world, player, scanQueue.poll(), range);
            if (prioritySectionsRemaining > 0) {
                prioritySectionsRemaining--;
            }
        }

        if (statusHoldTicks > 0) {
            statusHoldTicks--;
        } else if (!scanQueue.isEmpty()) {
            statusText = prioritySectionsRemaining > 0 ? "Scanning nearby..." : "Scanning wider area...";
        } else {
            statusText = "Ready";
        }
    }

    public void clear() {
        highlightsBySection.clear();
        scanQueue.clear();
        lastPlayerChunk = null;
        lastRange = -1;
        prioritySectionsRemaining = 0;
        immediateScanPending = false;
        statusText = "Ready";
        statusHoldTicks = 0;
    }

    public void requestRescan() {
        lastPlayerChunk = null;
        highlightsBySection.clear();
        scanQueue.clear();
    }

    public void requestPriorityRescan() {
        requestRescan();
        immediateScanPending = Boolean.TRUE.equals(config.fastScanOnToggle);
        prioritySectionsRemaining = Boolean.TRUE.equals(config.fastScanOnToggle) ? 256 : 0;
        statusText = Boolean.TRUE.equals(config.fastScanOnToggle) ? "Scanning nearby..." : "Scanning wider area...";
        statusHoldTicks = 0;
    }

    public void onChunkLoaded(ClientWorld world, ChunkPos pos) {
        if (config.hasEnabledBlocks() || smartHighlightManager.hasActiveBlockTargets()) {
            enqueueChunkSections(world, pos, MinecraftClient.getInstance().player == null
                    ? world.getBottomY()
                    : MinecraftClient.getInstance().player.getBlockY());
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
        statusText = "Scanning nearby...";
        statusHoldTicks = 0;
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

    private void rebuildQueue(ClientWorld world, ChunkPos center, int playerY, int range) {
        scanQueue.clear();
        int chunkRadius = Math.max(1, (int) Math.ceil(range / 16.0));
        List<ChunkPos> chunks = new ArrayList<>();
        for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                chunks.add(new ChunkPos(center.x + dx, center.z + dz));
            }
        }
        chunks.stream()
                .sorted(Comparator.comparingInt(chunk ->
                        square(chunk.x - center.x) + square(chunk.z - center.z)))
                .forEach(chunk -> enqueueChunkSections(world, chunk, playerY));
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
                    BlockRule rule = config.getEnabledBlockRule(state.getBlock());
                    if (rule == null) {
                        rule = smartHighlightManager.getEnabledBlockRule(state.getBlock());
                    }
                    if (rule == null) {
                        continue;
                    }

                    int ruleRange = config.useLoadedChunkRange ? scanRange : rule.range;
                    double rangeSq = (double) ruleRange * ruleRange;
                    if (player.squaredDistanceTo(mutable.getX() + 0.5, mutable.getY() + 0.5, mutable.getZ() + 0.5) > rangeSq) {
                        continue;
                    }

                    found.add(new BlockHighlight(mutable.toImmutable(), state, rule, HighlightConfig.parseColor(rule.color)));
                }
            }
        }

        if (found.isEmpty()) {
            highlightsBySection.remove(key);
        } else {
            highlightsBySection.put(key, found);
        }
    }

    public String getStatusText() {
        return statusText;
    }

    public int getCachedMatchCount() {
        return highlightsBySection.values().stream().mapToInt(List::size).sum();
    }

    public int getQueuedSectionCount() {
        return scanQueue.size();
    }

    private void runImmediateNearbyScan(ClientWorld world, ClientPlayerEntity player, int range) {
        ChunkPos center = player.getChunkPos();
        List<ScanSection> immediateSections = new ArrayList<>();
        Set<ScanSection> queued = new HashSet<>();
        int playerY = player.getBlockY();
        int playerSectionY = sectionStart(playerY);

        addImmediateSection(world, immediateSections, queued, center, playerSectionY);
        addBelowSections(world, immediateSections, queued, center, playerSectionY);
        addChunkSections(world, immediateSections, queued, center, playerY, true);

        int chunkRadius = Math.max(1, Math.min(2, (int) Math.ceil(range / 16.0)));
        for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                ChunkPos adjacent = new ChunkPos(center.x + dx, center.z + dz);
                addImmediateSection(world, immediateSections, queued, adjacent, playerSectionY);
                addBelowSections(world, immediateSections, queued, adjacent, playerSectionY);
                addChunkSections(world, immediateSections, queued, adjacent, playerY, false);
            }
        }

        int scannedBlocks = 0;
        int scannedSections = 0;
        for (ScanSection section : immediateSections) {
            if (scannedSections >= IMMEDIATE_SECTION_LIMIT || scannedBlocks >= IMMEDIATE_BLOCK_LIMIT) {
                break;
            }
            scanSection(world, player, section, range);
            scanQueue.removeIf(existing -> existing.matches(section.key()));
            scannedBlocks += SECTION_HEIGHT * 16 * 16;
            scannedSections++;
        }
    }

    private void enqueueChunkSections(ClientWorld world, ChunkPos chunkPos, int playerY) {
        List<ScanSection> sections = new ArrayList<>();
        addChunkSections(world, sections, chunkPos, playerY, false);
        sections.forEach(scanQueue::add);
    }

    private static void addChunkSections(ClientWorld world, List<ScanSection> target, ChunkPos chunkPos, int playerY, boolean belowFirst) {
        addChunkSections(world, target, null, chunkPos, playerY, belowFirst);
    }

    private static void addChunkSections(ClientWorld world, List<ScanSection> target, Set<ScanSection> dedupe, ChunkPos chunkPos, int playerY, boolean belowFirst) {
        int minY = Math.floorDiv(world.getBottomY(), SECTION_HEIGHT) * SECTION_HEIGHT;
        int maxY = world.getTopY();
        List<Integer> starts = new ArrayList<>();
        for (int y = minY; y < maxY; y += SECTION_HEIGHT) {
            starts.add(y);
        }
        starts.stream()
                .sorted((left, right) -> compareSectionPriority(left, right, playerY, belowFirst))
                .forEach(y -> addImmediateSection(world, target, dedupe, chunkPos, y));
    }

    private static void addBelowSections(ClientWorld world, List<ScanSection> target, Set<ScanSection> dedupe, ChunkPos chunkPos, int playerSectionY) {
        int minY = Math.floorDiv(world.getBottomY(), SECTION_HEIGHT) * SECTION_HEIGHT;
        for (int y = playerSectionY - SECTION_HEIGHT; y >= minY; y -= SECTION_HEIGHT) {
            addImmediateSection(world, target, dedupe, chunkPos, y);
        }
    }

    private static void addImmediateSection(ClientWorld world, List<ScanSection> target, Set<ScanSection> dedupe, ChunkPos chunkPos, int sectionY) {
        if (world.getChunk(chunkPos.x, chunkPos.z, ChunkStatus.FULL, false) == null) {
            return;
        }
        ScanSection section = new ScanSection(chunkPos.x, chunkPos.z, sectionY);
        if (dedupe == null || dedupe.add(section)) {
            target.add(section);
        }
    }

    private static int sectionStart(int y) {
        return Math.floorDiv(y, SECTION_HEIGHT) * SECTION_HEIGHT;
    }

    private static int compareSectionPriority(int left, int right, int playerY, boolean belowFirst) {
        if (belowFirst) {
            boolean leftBelow = left + SECTION_HEIGHT / 2 <= playerY;
            boolean rightBelow = right + SECTION_HEIGHT / 2 <= playerY;
            if (leftBelow != rightBelow) {
                return leftBelow ? -1 : 1;
            }
        }
        return Integer.compare(Math.abs(left + SECTION_HEIGHT / 2 - playerY), Math.abs(right + SECTION_HEIGHT / 2 - playerY));
    }

    private int effectiveScanRange(MinecraftClient client) {
        if (!config.useLoadedChunkRange) {
            return Math.max(config.maxEnabledBlockRange(), smartHighlightManager.activeBlockRange());
        }
        return Math.max(16, client.options.getViewDistance().getValue() * 16);
    }

    private int effectiveRuleRange(BlockRule rule) {
        return config.useLoadedChunkRange && lastRange > 0 ? lastRange : rule.range;
    }

    private static int square(int value) {
        return value * value;
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

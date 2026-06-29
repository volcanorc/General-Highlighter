package com.codex.glow.highlight;

import com.codex.glow.config.BlockRule;
import net.minecraft.util.math.BlockPos;

public record BlockHighlight(BlockPos pos, BlockRule rule, int color) {
}

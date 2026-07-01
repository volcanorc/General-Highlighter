package com.codex.glow.highlight;

import net.minecraft.block.BlockState;
import com.codex.glow.config.BlockRule;
import net.minecraft.util.math.BlockPos;

public record BlockHighlight(BlockPos pos, BlockState state, BlockRule rule, int color) {
}

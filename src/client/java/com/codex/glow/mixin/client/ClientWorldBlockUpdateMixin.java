package com.codex.glow.mixin.client;

import com.codex.glow.ClientGlowHighlighterMod;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientWorld.class)
public abstract class ClientWorldBlockUpdateMixin {
    @Inject(method = "handleBlockUpdate", at = @At("TAIL"))
    private void generalHighlighter$onBlockUpdate(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
        ClientGlowHighlighterMod.onClientBlockUpdated(pos);
    }
}

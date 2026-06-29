package com.codex.glow.mixin.client;

import com.codex.glow.ClientGlowHighlighterMod;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityColorMixin {
    @Inject(method = "getTeamColorValue", at = @At("HEAD"), cancellable = true)
    private void clientGlowHighlighter$getTeamColorValue(CallbackInfoReturnable<Integer> cir) {
        Entity entity = (Entity) (Object) this;
        Integer color = ClientGlowHighlighterMod.getEntityGlowColor(entity);
        if (color != null) {
            cir.setReturnValue(color);
        }
    }
}

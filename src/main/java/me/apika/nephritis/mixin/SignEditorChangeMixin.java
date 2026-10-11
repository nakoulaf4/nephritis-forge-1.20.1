package me.apika.nephritis.mixin;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SignBlockEntity.class)
public abstract class SignEditorChangeMixin {
    @Inject(method = "setAllowedPlayerEditor(Ljava/util/UUID;)V", at = @At("RETURN"))
    private void nephritis$reevaluateTicker(UUID editor, CallbackInfo ci) {
        BlockEntity self = (BlockEntity) (Object) this;
        Level level = self.getLevel();
        if (!(level instanceof ServerLevel)) return;
        BlockPos pos = self.getBlockPos();
        LevelChunk chunk = level.getChunkAt(pos);
        ((LevelChunkInvokerMixin) chunk).nephritis$updateBlockEntityTicker(self);
    }
}

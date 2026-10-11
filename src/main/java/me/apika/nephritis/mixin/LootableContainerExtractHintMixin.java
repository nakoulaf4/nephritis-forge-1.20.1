package me.apika.nephritis.mixin;

import me.apika.nephritis.hopper.ExtractHint;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;


@Mixin(RandomizableContainerBlockEntity.class)
public abstract class LootableContainerExtractHintMixin implements Container, ExtractHint {
    @Unique
    private int nephritis$extractHint = 0;

    @Override
    public int nephritis$getExtractHint() {
        return nephritis$extractHint;
    }

    @Override
    public void nephritis$setExtractHint(int hint) {
        nephritis$extractHint = Math.max(0, hint);
    }

    @Unique
    private void nephritis$advanceHintFrom(int from) {
        int size = getContainerSize();
        int next = size;
        for (int i = Math.max(0, from); i < size; i++) {
            if (!getItem(i).isEmpty()) {
                next = i;
                break;
            }
        }
        nephritis$extractHint = next;
    }

    @Inject(method = "setItem", at = @At("RETURN"))
    private void nephritis$onSetStack(int slot, ItemStack stack, CallbackInfo ci) {
        if (!stack.isEmpty() && slot < nephritis$extractHint) {
            nephritis$extractHint = slot;
        }
    }

    @Inject(method = "removeItem(II)Lnet/minecraft/world/item/ItemStack;", at = @At("RETURN"))
    private void nephritis$onRemoveStackAmount(int slot, int amount, CallbackInfoReturnable<ItemStack> cir) {
        if (slot == nephritis$extractHint && getItem(slot).isEmpty()) {
            nephritis$advanceHintFrom(slot + 1);
        }
    }

    @Inject(method = "removeItemNoUpdate(I)Lnet/minecraft/world/item/ItemStack;", at = @At("RETURN"))
    private void nephritis$onRemoveStackAll(int slot, CallbackInfoReturnable<ItemStack> cir) {
        if (slot == nephritis$extractHint && getItem(slot).isEmpty()) {
            nephritis$advanceHintFrom(slot + 1);
        }
    }
}

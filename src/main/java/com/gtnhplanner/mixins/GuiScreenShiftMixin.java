package com.gtnhplanner.mixins;

import net.minecraft.client.gui.GuiScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.gtnhplanner.ui.tutorial.Pointer;

/** The tour's Shift-clicks: while it holds Shift, the game's "is Shift down" says yes. */
@Mixin(GuiScreen.class)
public abstract class GuiScreenShiftMixin {

    @Inject(method = "isShiftKeyDown", at = @At("HEAD"), cancellable = true)
    private static void gtnhplanner$tourShift(final CallbackInfoReturnable<Boolean> cir) {
        if (Pointer.shift) cir.setReturnValue(true);
    }
}

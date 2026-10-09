package com.gtnhplanner.mixins;

import org.lwjgl.input.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.gtnhplanner.ui.tutorial.Pointer;

import codechicken.lib.gui.GuiDraw;

/**
 * NEI reads the mouse itself (its item list, its tips, an item being dragged): while the tour drives, it reads the
 * tour's pointer, as the screens do.
 */
@Mixin(value = GuiDraw.class, remap = false)
public class GuiDrawMixin {

    @Redirect(
        method = "getMousePosition()Ljava/awt/Point;",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/input/Mouse;getX()I"),
        require = 0)
    private static int gtnhplanner$mouseX() {
        return Pointer.windowX(Mouse.getX());
    }

    @Redirect(
        method = "getMousePosition()Ljava/awt/Point;",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/input/Mouse;getY()I"),
        require = 0)
    private static int gtnhplanner$mouseY() {
        return Pointer.windowY(Mouse.getY());
    }
}

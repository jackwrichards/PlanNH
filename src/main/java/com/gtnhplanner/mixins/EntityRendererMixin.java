package com.gtnhplanner.mixins;

import net.minecraft.client.renderer.EntityRenderer;

import org.lwjgl.input.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.gtnhplanner.ui.tutorial.Pointer;

/**
 * The mouse every screen is drawn with comes from here, once a frame: while the tour drives, it is the tour's pointer
 * (screens hover and tip what the tour points at). Not required, so a mod that rewrites this method costs the tour its
 * hovering, never the game.
 */
@Mixin(EntityRenderer.class)
public class EntityRendererMixin {

    @Redirect(
        method = "updateCameraAndRender",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/input/Mouse;getX()I", remap = false),
        require = 0)
    private int gtnhplanner$mouseX() {
        return Pointer.windowX(Mouse.getX());
    }

    @Redirect(
        method = "updateCameraAndRender",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/input/Mouse;getY()I", remap = false),
        require = 0)
    private int gtnhplanner$mouseY() {
        return Pointer.windowY(Mouse.getY());
    }
}

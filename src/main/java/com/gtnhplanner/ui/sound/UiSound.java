package com.gtnhplanner.ui.sound;

import net.minecraft.client.audio.PositionedSound;
import net.minecraft.util.ResourceLocation;

/** One of the planner's sounds as the game plays it: heard the same wherever the player stands, never repeating. */
final class UiSound extends PositionedSound {

    UiSound(final ResourceLocation where, final float volume, final float pitch) {
        super(where);
        this.volume = volume;
        this.field_147663_c = pitch;
        this.field_147666_i = AttenuationType.NONE;
    }
}

package dev.mapframes.access;

import net.minecraft.resources.Identifier;

/** Per-extraction texture snapshot, separate from Minecraft's original map texture. */
public interface MapRenderStateAccess {
    Identifier mapframes$getTexture();

    void mapframes$setTexture(Identifier texture);
}

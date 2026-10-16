package dev.mapframes.mixin;

import dev.mapframes.xaero.XaeroBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.map.region.texture.LeafRegionTexture;

/** Captures historical tiles after both the base cache and leaf metadata have been read. */
@Mixin(value = LeafRegionTexture.class, remap = false)
public abstract class XaeroLeafRegionTextureMixin {
    @Inject(
            method = "readCacheData(IILjava/io/DataInputStream;[B[BLxaero/map/region/LeveledRegion;Lxaero/map/MapProcessor;IIZ)V",
            at = @At("TAIL"),
            require = 1
    )
    private void mapframes$captureCachedTile(CallbackInfo callback) {
        XaeroBridge.remember((LeafRegionTexture) (Object) this);
    }
}

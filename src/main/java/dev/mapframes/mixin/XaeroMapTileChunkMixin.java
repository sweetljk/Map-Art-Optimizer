package dev.mapframes.mixin;

import dev.mapframes.xaero.XaeroBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.map.region.MapTileChunk;

/** Copies a tile only after Xaero has finished updating its terrain colors. */
@Mixin(value = MapTileChunk.class, remap = false)
public abstract class XaeroMapTileChunkMixin {
    @Inject(
            method = "updateBuffers(Lxaero/map/MapProcessor;Lxaero/map/biome/BlockTintProvider;Lxaero/map/region/OverlayManager;ZLxaero/map/cache/BlockStateShortShapeCache;Lxaero/map/region/MapUpdateFastConfig;)V",
            at = @At("TAIL"),
            require = 1
    )
    private void mapframes$captureUpdatedTile(CallbackInfo callback) {
        XaeroBridge.remember(((MapTileChunk) (Object) this).getLeafTexture());
    }
}

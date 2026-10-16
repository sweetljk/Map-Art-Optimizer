package dev.mapframes.mixin;

import dev.mapframes.access.MapRenderStateAccess;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = MapRenderState.class, remap = false)
public abstract class MapRenderStateMixin implements MapRenderStateAccess {
    @Unique
    private Identifier mapframes$texture;

    @Override
    public Identifier mapframes$getTexture() {
        return mapframes$texture;
    }

    @Override
    public void mapframes$setTexture(Identifier texture) {
        mapframes$texture = texture;
    }
}

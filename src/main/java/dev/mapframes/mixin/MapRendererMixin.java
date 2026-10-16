package dev.mapframes.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.mapframes.MapFramesClient;
import dev.mapframes.access.MapRenderStateAccess;
import net.minecraft.client.renderer.MapRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces only the map background; vanilla frame transforms and decorations remain intact. */
@Mixin(value = MapRenderer.class, remap = false)
public abstract class MapRendererMixin {
    @Inject(
            method = "extractRenderState(Lnet/minecraft/world/level/saveddata/maps/MapId;Lnet/minecraft/world/level/saveddata/maps/MapItemSavedData;Lnet/minecraft/client/renderer/state/MapRenderState;)V",
            at = @At("TAIL"),
            require = 1
    )
    private void mapframes$captureTexture(MapId mapId, MapItemSavedData mapData,
                                         MapRenderState state, CallbackInfo callback) {
        ((MapRenderStateAccess) state).mapframes$setTexture(MapFramesClient.textureFor(mapId, mapData));
    }

    @Redirect(
            method = "render(Lnet/minecraft/client/renderer/state/MapRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ZI)V",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/client/renderer/state/MapRenderState;texture:Lnet/minecraft/resources/Identifier;"
            ),
            require = 1
    )
    private Identifier mapframes$frameTexture(MapRenderState owner, MapRenderState state,
                                              PoseStack poseStack, SubmitNodeCollector collector,
                                              boolean showOnlyFrame, int lightCoords) {
        // ItemFrameRenderer supplies true; hand rendering supplies false. Do not mutate state:
        // the collector may submit this texture for drawing after this method has returned.
        Identifier replacement = ((MapRenderStateAccess) owner).mapframes$getTexture();
        return showOnlyFrame && replacement != null ? replacement : owner.texture;
    }

    @Redirect(
            method = "render(Lnet/minecraft/client/renderer/state/MapRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ZI)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitCustomGeometry(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;Lnet/minecraft/client/renderer/SubmitNodeCollector$CustomGeometryRenderer;)V",
                    ordinal = 0
            ),
            require = 1
    )
    private void mapframes$frameGeometry(SubmitNodeCollector owner, PoseStack submittedPose,
                                        RenderType renderType, SubmitNodeCollector.CustomGeometryRenderer geometry,
                                        MapRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                                        boolean showOnlyFrame, int lightCoords) {
        // Redirects run after ModifyArg injectors. ImmediatelyFast may already have
        // replaced this callback with UVs selecting one 128px cell in its map atlas.
        // Our HD texture fills its entire image, so only HD frame backgrounds need
        // vanilla 0..1 UVs. Keep its callback for hand-held and unbound maps.
        if (showOnlyFrame && ((MapRenderStateAccess) state).mapframes$getTexture() != null) {
            geometry = (pose, vertices) -> {
                vertices.addVertex(pose, 0.0F, 128.0F, -0.01F).setColor(-1).setUv(0.0F, 1.0F).setLight(lightCoords);
                vertices.addVertex(pose, 128.0F, 128.0F, -0.01F).setColor(-1).setUv(1.0F, 1.0F).setLight(lightCoords);
                vertices.addVertex(pose, 128.0F, 0.0F, -0.01F).setColor(-1).setUv(1.0F, 0.0F).setLight(lightCoords);
                vertices.addVertex(pose, 0.0F, 0.0F, -0.01F).setColor(-1).setUv(0.0F, 0.0F).setLight(lightCoords);
            };
        }
        owner.submitCustomGeometry(submittedPose, renderType, geometry);
    }
}

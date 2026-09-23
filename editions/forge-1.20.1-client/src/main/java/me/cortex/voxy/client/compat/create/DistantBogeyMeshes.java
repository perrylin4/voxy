package me.cortex.voxy.client.compat.create;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllBogeyStyles;
import me.cortex.voxy.client.compat.distant.DistantMesh;
import me.cortex.voxy.client.compat.distant.DistantVertexCapture;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

final class DistantBogeyMeshes {
    private static final Map<String, DistantMesh> CACHE = new HashMap<>();
    private static boolean errored;

    private DistantBogeyMeshes() {}

    static DistantMesh get(ResourceLocation styleId, ResourceLocation sizeId, CompoundTag data) {
        String key = styleId + "|" + sizeId;
        if (CACHE.containsKey(key)) return CACHE.get(key);
        DistantMesh mesh = null;
        try {
            var style = AllBogeyStyles.BOGEY_STYLES.get(styleId);
            if (style != null) {
                var size = style.validSizes().stream().filter(candidate -> candidate.id().equals(sizeId))
                        .findFirst().orElseGet(() -> style.validSizes().stream().findFirst().orElse(null));
                if (size != null) {
                    var builder = new DistantMesh.Builder();
                    try {
                        var capture = new DistantVertexCapture(builder, false);
                        style.render(size, 0.0f, new PoseStack(), capture, LightTexture.FULL_BRIGHT,
                                OverlayTexture.NO_OVERLAY, 0.0f, data, true);
                        mesh = builder.build();
                    } catch (Throwable t) {
                        builder.discard();
                        throw t;
                    }
                }
            }
        } catch (Throwable t) {
            if (!errored) {
                errored = true;
                Logger.error("Distant bogey capture failed for " + key, t);
            }
        }
        CACHE.put(key, mesh);
        return mesh;
    }
}

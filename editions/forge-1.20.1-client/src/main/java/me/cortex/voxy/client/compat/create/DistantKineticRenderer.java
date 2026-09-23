package me.cortex.voxy.client.compat.create;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import me.cortex.voxy.client.compat.LodPipelineHooks;
import me.cortex.voxy.client.compat.distant.DistantBlockShader;
import me.cortex.voxy.client.compat.distant.DistantMesh;
import me.cortex.voxy.client.compat.distant.DistantVertexCapture;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.rendering.Viewport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;

public final class DistantKineticRenderer implements LodPipelineHooks.Renderer {
    public static final DistantKineticRenderer INSTANCE = new DistantKineticRenderer();
    private static final ThreadLocal<Boolean> CAPTURING = ThreadLocal.withInitial(() -> false);
    private final Map<Long, Snapshot> snapshots = new HashMap<>();
    private ResourceKey<Level> dimension;
    private int scanCursor;

    private record Snapshot(int x, int y, int z, int stateHash, DistantMesh mesh) {}

    private DistantKineticRenderer() {}

    public static boolean isCapturing() {
        return CAPTURING.get();
    }

    public static boolean shouldCullLive(BlockPos pos, net.minecraft.world.phys.Vec3 camera) {
        Snapshot snapshot = INSTANCE.snapshots.get(pos.asLong());
        if (snapshot == null || snapshot.mesh == null || !VoxyConfig.CONFIG.distantKinetics) return false;
        double reach = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0;
        return pos.distToCenterSqr(camera.x, camera.y, camera.z) > reach * reach;
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null || !VoxyConfig.CONFIG.isRenderingEnabled() || !VoxyConfig.CONFIG.distantKinetics) {
            this.clear();
            return;
        }
        if (!mc.level.dimension().equals(this.dimension)) {
            this.clear();
            this.dimension = mc.level.dimension();
            this.restore(mc.level);
        }
        int radius = mc.options.getEffectiveRenderDistance() + 1;
        int side = radius * 2 + 1;
        int centerX = BlockPos.containing(mc.gameRenderer.getMainCamera().getPosition()).getX() >> 4;
        int centerZ = BlockPos.containing(mc.gameRenderer.getMainCamera().getPosition()).getZ() >> 4;
        for (int i = 0; i < 8; i++) {
            int index = this.scanCursor++ % (side * side);
            int cx = centerX + index % side - radius;
            int cz = centerZ + index / side - radius;
            LevelChunk chunk = mc.level.getChunkSource().getChunk(cx, cz, false);
            if (chunk != null) this.captureChunk(mc.level, chunk, false);
        }
        var camera = mc.gameRenderer.getMainCamera().getPosition();
        double max = (VoxyConfig.CONFIG.distantKineticMaxChunks == 0
                ? VoxyConfig.CONFIG.getLodRenderDistanceBlocks()
                : VoxyConfig.CONFIG.distantKineticMaxChunks * 16.0) + 32.0;
        double maxSq = max * max;
        this.snapshots.entrySet().removeIf(entry -> {
            Snapshot snapshot = entry.getValue();
            double dx = snapshot.x - camera.x;
            double dy = snapshot.y - camera.y;
            double dz = snapshot.z - camera.z;
            if (dx * dx + dy * dy + dz * dz <= maxSq) return false;
            snapshot.mesh.free();
            return true;
        });
        this.syncFlywheelVisibility(mc);
    }

    @SubscribeEvent
    public void chunkUnload(ChunkEvent.Unload event) {
        var mc = Minecraft.getInstance();
        if (mc.level != null && event.getLevel() == mc.level && event.getChunk() instanceof LevelChunk chunk) {
            this.captureChunk(mc.level, chunk, true);
        }
    }

    @SubscribeEvent
    public void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        this.clear();
        this.dimension = null;
    }

    private void captureChunk(ClientLevel level, LevelChunk chunk, boolean unloading) {
        var present = unloading ? null : new HashSet<Long>();
        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            if (!(blockEntity instanceof KineticBlockEntity kinetic)) continue;
            long key = kinetic.getBlockPos().asLong();
            if (present != null) present.add(key);
            int stateHash = kinetic.getBlockState().hashCode();
            Snapshot old = this.snapshots.get(key);
            if (old != null && old.stateHash == stateHash) continue;
            DistantMesh.Built built = capture(kinetic);
            if (built.mesh() == null) continue;
            if (old != null) old.mesh.free();
            var pos = kinetic.getBlockPos();
            this.snapshots.put(key, new Snapshot(pos.getX(), pos.getY(), pos.getZ(), stateHash, built.mesh()));
            CreateSnapshotStore.saveKinetic(level, new CreateSnapshotStore.Kinetic(
                    key, pos.getX(), pos.getY(), pos.getZ(), stateHash, built.bytes()));
        }
        if (present != null) {
            int cx = chunk.getPos().x;
            int cz = chunk.getPos().z;
            this.snapshots.entrySet().removeIf(entry -> {
                Snapshot snapshot = entry.getValue();
                if ((snapshot.x >> 4) != cx || (snapshot.z >> 4) != cz || present.contains(entry.getKey())) return false;
                snapshot.mesh.free();
                CreateSnapshotStore.removeKinetic(level, entry.getKey());
                return true;
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static DistantMesh.Built capture(KineticBlockEntity kinetic) {
        var dispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();
        var renderer = dispatcher.getRenderer(kinetic);
        if (renderer == null) return new DistantMesh.Built(null, null);
        var builder = new DistantMesh.Builder();
        int light = LevelRenderer.getLightColor(kinetic.getLevel(), kinetic.getBlockPos());
        int blockLight = light & 0xFFFF;
        int skyLight = light >>> 16 & 0xFFFF;
        var capture = new DistantVertexCapture(builder, true, blockLight, skyLight);
        CAPTURING.set(true);
        try {
            ((net.minecraft.client.renderer.blockentity.BlockEntityRenderer<KineticBlockEntity>) renderer)
                    .render(kinetic, 1.0f, new PoseStack(), capture, light, OverlayTexture.NO_OVERLAY);
        } catch (Throwable ignored) {
        } finally {
            CAPTURING.set(false);
        }
        return builder.buildPersistent();
    }

    private void restore(ClientLevel level) {
        int restored = 0;
        for (var stored : CreateSnapshotStore.loadKinetics(level)) {
            DistantMesh mesh = DistantMesh.fromBytes(stored.mesh());
            if (mesh == null) continue;
            Snapshot old = this.snapshots.put(stored.key(), new Snapshot(
                    stored.x(), stored.y(), stored.z(), stored.stateHash(), mesh));
            if (old != null) old.mesh.free();
            restored++;
        }
        if (restored != 0) me.cortex.voxy.common.Logger.info("Restored " + restored + " Create kinetic LOD snapshot(s)");
    }

    @Override
    public void render(AbstractRenderPipeline pipeline, Viewport<?> viewport, int depthFunc) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().equals(this.dimension)
                || !VoxyConfig.CONFIG.distantKinetics || this.snapshots.isEmpty()) return;
        double min = Math.max(16.0, mc.options.getEffectiveRenderDistance() * 16.0);
        double minSq = min * min;
        double max = VoxyConfig.CONFIG.distantKineticMaxChunks == 0
                ? VoxyConfig.CONFIG.getLodRenderDistanceBlocks()
                : VoxyConfig.CONFIG.distantKineticMaxChunks * 16.0;
        double maxSq = max * max;
        boolean bound = false;
        Matrix4f transform = new Matrix4f();
        pipeline.setupAndBindOpaque(viewport);
        for (Snapshot snapshot : this.snapshots.values()) {
            double dx = snapshot.x - viewport.cameraX;
            double dy = snapshot.y - viewport.cameraY;
            double dz = snapshot.z - viewport.cameraZ;
            double distanceSq = dx * dx + dy * dy + dz * dz;
            if (distanceSq < minSq || distanceSq > maxSq) continue;
            if (!bound) {
                DistantBlockShader.get(pipeline).bind();
                DistantBlockShader.bindTextures();
                glEnable(GL_DEPTH_TEST);
                glDepthFunc(depthFunc);
                glDepthMask(true);
                glDisable(GL_CULL_FACE);
                glDisable(GL_BLEND);
                glEnable(GL_STENCIL_TEST);
                glStencilFunc(GL_ALWAYS, 3, 0xFF);
                glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
                bound = true;
            }
            transform.set(viewport.MVP).translate((float) dx, (float) dy, (float) dz);
            DistantBlockShader.uploadTransform(transform);
            snapshot.mesh.draw();
        }
        if (bound) {
            glBindVertexArray(0);
            glUseProgram(0);
            glStencilFunc(GL_EQUAL, 1, 0xFF);
            glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
        }
    }

    private void clear() {
        this.setFlywheelVisibility(false);
        for (Snapshot snapshot : this.snapshots.values()) snapshot.mesh.free();
        this.snapshots.clear();
        this.scanCursor = 0;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void syncFlywheelVisibility(Minecraft mc) {
        var manager = dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl.get(mc.level);
        if (manager == null) return;
        var visualManager = (dev.engine_room.flywheel.impl.visualization.VisualManagerImpl) manager.blockEntities();
        var storage = (dev.engine_room.flywheel.impl.visualization.storage.BlockEntityStorage) visualManager.getStorage();
        var camera = mc.gameRenderer.getMainCamera().getPosition();
        for (var entry : this.snapshots.entrySet()) {
            var visual = storage.visualAtPos(entry.getKey());
            if (visual == null) continue;
            boolean hidden = shouldCullLive(BlockPos.of(entry.getKey()), camera);
            visual.collectCrumblingInstances(instance -> {
                if (instance != null) {
                    instance.setVisible(!hidden);
                    if (!hidden) instance.setChanged();
                }
            });
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void setFlywheelVisibility(boolean hidden) {
        var mc = Minecraft.getInstance();
        var manager = dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl.get(mc.level);
        if (manager == null) return;
        var visualManager = (dev.engine_room.flywheel.impl.visualization.VisualManagerImpl) manager.blockEntities();
        var storage = (dev.engine_room.flywheel.impl.visualization.storage.BlockEntityStorage) visualManager.getStorage();
        for (long key : this.snapshots.keySet()) {
            var visual = storage.visualAtPos(key);
            if (visual == null) continue;
            visual.collectCrumblingInstances(instance -> {
                if (instance != null) {
                    instance.setVisible(!hidden);
                    if (!hidden) instance.setChanged();
                }
            });
        }
    }
}

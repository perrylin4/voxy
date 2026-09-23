package me.cortex.voxy.client.compat.create;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import me.cortex.voxy.client.compat.LodPipelineHooks;
import me.cortex.voxy.client.compat.distant.DistantBlockShader;
import me.cortex.voxy.client.compat.distant.DistantMesh;
import me.cortex.voxy.client.compat.distant.DistantVertexCapture;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.rendering.Viewport;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;

public final class DistantCreateRenderer implements LodPipelineHooks.Renderer {
    public static final DistantCreateRenderer INSTANCE = new DistantCreateRenderer();
    private static final PoseStack POSE = new PoseStack();
    private final Map<UUID, Snapshot> snapshots = new HashMap<>();
    private ResourceKey<Level> dimension;

    private static final class Snapshot {
        DistantMesh mesh;
        byte[] meshBytes;
        final Matrix4f local = new Matrix4f();
        double x;
        double y;
        double z;
        long signature;
        long lastSeen;
        boolean train;
        boolean live;
        int entityId = -1;
        int light = net.minecraft.client.renderer.LightTexture.FULL_BRIGHT;
        long lightSampledAt;
        List<IntegratedContraptionTracker.BogeyInfo> bogeyInfo = List.of();
        List<IntegratedContraptionTracker.BogeyPose> previousBogeys = List.of();
        List<IntegratedContraptionTracker.BogeyPose> currentBogeys = List.of();
        long bogeyReceivedNanos;
        long bogeyIntervalNanos = 250_000_000L;
        IntegratedContraptionTracker.Track remoteTrack;
    }

    private DistantCreateRenderer() {}

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null || !VoxyConfig.CONFIG.isRenderingEnabled()
                || (!VoxyConfig.CONFIG.distantContraptions && !VoxyConfig.CONFIG.distantTrains)) {
            this.clear();
            return;
        }
        if (!mc.level.dimension().equals(this.dimension)) {
            this.clear();
            this.dimension = mc.level.dimension();
            this.restore(mc.level);
        }

        long now = System.currentTimeMillis();
        var seen = new HashSet<UUID>();
        for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof AbstractContraptionEntity contraptionEntity)) continue;
            var contraption = contraptionEntity.getContraption();
            if (contraption == null || contraption.getBlocks().isEmpty()) continue;
            UUID id = snapshotId(contraptionEntity);
            seen.add(id);
            Snapshot snapshot = this.snapshots.computeIfAbsent(id, ignored -> new Snapshot());
            snapshot.train = entity instanceof CarriageContraptionEntity;
            snapshot.live = true;
            snapshot.x = entity.getX();
            snapshot.y = entity.getY();
            snapshot.z = entity.getZ();
            snapshot.lastSeen = now;
            snapshot.entityId = entity.getId();
            if (now - snapshot.lightSampledAt >= 1000) {
                snapshot.light = sampleLight(mc.level, snapshot.x, snapshot.y, snapshot.z);
                snapshot.lightSampledAt = now;
            }

            long signature = 1;
            for (var entry : contraption.getBlocks().entrySet()) {
                signature = signature * 31 + entry.getKey().asLong();
                signature = signature * 31 + entry.getValue().state().hashCode();
            }
            boolean meshChanged = false;
            if (snapshot.mesh == null || snapshot.signature != signature) {
                DistantMesh.Built replacement = bake(contraptionEntity);
                if (replacement.mesh() != null) {
                    if (snapshot.mesh != null) snapshot.mesh.free();
                    snapshot.mesh = replacement.mesh();
                    snapshot.meshBytes = replacement.bytes();
                    snapshot.signature = signature;
                    meshChanged = true;
                }
            }

            POSE.pushPose();
            try {
                contraptionEntity.applyLocalTransforms(POSE, 1.0f);
                snapshot.local.set(POSE.last().pose());
            } catch (Throwable ignored) {
            } finally {
                POSE.popPose();
            }
            if (meshChanged) this.save(mc.level, id, snapshot);
        }

        IntegratedContraptionTracker.update(mc, this.snapshots, seen);

        double reach = mc.options.getEffectiveRenderDistance() * 16.0;
        double reachSq = reach * reach;
        var camera = mc.gameRenderer.getMainCamera().getPosition();
        this.snapshots.entrySet().removeIf(entry -> {
            Snapshot snapshot = entry.getValue();
            if (seen.contains(entry.getKey())) return false;
            if (snapshot.live) this.save(mc.level, entry.getKey(), snapshot);
            snapshot.live = false;
            double dx = snapshot.x - camera.x;
            double dy = snapshot.y - camera.y;
            double dz = snapshot.z - camera.z;
            if (now - snapshot.lastSeen >= 2000 && dx * dx + dy * dy + dz * dz < reachSq) {
                if (snapshot.mesh != null) snapshot.mesh.free();
                return true;
            }
            double max = distanceFor(snapshot) + 32.0;
            if (dx * dx + dy * dy + dz * dz > max * max) {
                if (snapshot.mesh != null) snapshot.mesh.free();
                return true;
            }
            return false;
        });
    }

    @SubscribeEvent
    public void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        var level = Minecraft.getInstance().level;
        if (level != null) {
            for (var entry : this.snapshots.entrySet()) this.save(level, entry.getKey(), entry.getValue());
        }
        CreateSnapshotStore.flush();
        this.clear();
        this.dimension = null;
    }

    @Override
    public void render(AbstractRenderPipeline pipeline, Viewport<?> viewport, int depthFunc) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().equals(this.dimension) || this.snapshots.isEmpty()) return;
        double vanilla = Math.max(16.0, mc.options.getEffectiveRenderDistance() * 16.0);
        double vanillaSq = vanilla * vanilla;
        boolean bound = false;
        Matrix4f transform = new Matrix4f();
        Matrix4f liveLocal = new Matrix4f();
        pipeline.setupAndBindOpaque(viewport);
        for (Snapshot snapshot : this.snapshots.values()) {
            if (snapshot.mesh == null || snapshot.train && !VoxyConfig.CONFIG.distantTrains
                    || !snapshot.train && !VoxyConfig.CONFIG.distantContraptions) continue;
            double worldX = snapshot.x;
            double worldY = snapshot.y;
            double worldZ = snapshot.z;
            Matrix4f local = snapshot.local;
            var entity = snapshot.entityId < 0 ? null : mc.level.getEntity(snapshot.entityId);
            if (entity instanceof AbstractContraptionEntity contraptionEntity && !entity.isRemoved()) {
                float partial = mc.getFrameTime();
                worldX = net.minecraft.util.Mth.lerp(partial, entity.xOld, entity.getX());
                worldY = net.minecraft.util.Mth.lerp(partial, entity.yOld, entity.getY());
                worldZ = net.minecraft.util.Mth.lerp(partial, entity.zOld, entity.getZ());
                POSE.pushPose();
                try {
                    contraptionEntity.applyLocalTransforms(POSE, partial);
                    local = liveLocal.set(POSE.last().pose());
                } catch (Throwable ignored) {
                } finally {
                    POSE.popPose();
                }
            } else if (snapshot.remoteTrack != null && snapshot.remoteTrack.current != null) {
                var current = snapshot.remoteTrack.current;
                var previous = snapshot.remoteTrack.previous == null ? current : snapshot.remoteTrack.previous;
                float amount = Math.max(0.0f, Math.min(1.25f,
                        (float) (System.nanoTime() - current.receivedNanos()) / snapshot.remoteTrack.intervalNanos));
                worldX = net.minecraft.util.Mth.lerp(amount, previous.x(), current.x());
                worldY = net.minecraft.util.Mth.lerp(amount, previous.y(), current.y());
                worldZ = net.minecraft.util.Mth.lerp(amount, previous.z(), current.z());
                if (current.train()) {
                    float yaw = previous.yaw() + net.minecraft.util.Mth.wrapDegrees(
                            current.yaw() - previous.yaw()) * amount;
                    float pitch = net.minecraft.util.Mth.lerp(amount, previous.pitch(), current.pitch());
                    local = liveLocal.identity().translate(0.0f, 0.5f, 0.0f)
                            .rotateY((float) Math.toRadians(-yaw))
                            .rotateZ((float) Math.toRadians(pitch))
                            .rotateY((float) Math.toRadians(current.initialYaw()))
                            .translate(-0.5f, -0.5f, -0.5f);
                }
            }
            double dx = worldX - viewport.cameraX;
            double dy = worldY - viewport.cameraY;
            double dz = worldZ - viewport.cameraZ;
            double distanceSq = dx * dx + dy * dy + dz * dz;
            double max = distanceFor(snapshot);
            boolean liveOwns = entity instanceof AbstractContraptionEntity
                    && !entity.isRemoved()
                    && (!(entity instanceof CarriageContraptionEntity carriage) || carriage.validForRender);
            if (liveOwns && distanceSq < vanillaSq || distanceSq > max * max) continue;
            if (!bound) {
                DistantBlockShader.get(pipeline, true).bind();
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
            transform.set(viewport.MVP).translate((float) dx, (float) dy, (float) dz).mul(local);
            DistantBlockShader.uploadTransform(transform);
            DistantBlockShader.uploadLight(snapshot.light);
            snapshot.mesh.draw();
            if (snapshot.train && !snapshot.currentBogeys.isEmpty() && !snapshot.bogeyInfo.isEmpty()) {
                renderBogeys(snapshot, viewport, transform);
            }
        }
        if (bound) {
            glBindVertexArray(0);
            glUseProgram(0);
            glStencilFunc(GL_EQUAL, 1, 0xFF);
            glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
        }
    }

    public static boolean shouldCullLive(AbstractContraptionEntity entity,
                                         net.minecraft.world.phys.Vec3 camera) {
        Snapshot snapshot = INSTANCE.snapshots.get(snapshotId(entity));
        if (snapshot == null || snapshot.mesh == null) return false;
        if (snapshot.train ? !VoxyConfig.CONFIG.distantTrains : !VoxyConfig.CONFIG.distantContraptions) return false;
        double reach = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0;
        return entity.position().distanceToSqr(camera) > reach * reach;
    }

    private static double distanceFor(Snapshot snapshot) {
        int chunks = snapshot.train ? VoxyConfig.CONFIG.distantTrainMaxChunks
                : VoxyConfig.CONFIG.distantContraptionMaxChunks;
        return chunks == 0 ? VoxyConfig.CONFIG.getLodRenderDistanceBlocks() : chunks * 16.0;
    }

    private static UUID snapshotId(AbstractContraptionEntity entity) {
        if (entity instanceof CarriageContraptionEntity carriage && carriage.trainId != null) {
            return IntegratedContraptionTracker.carriageId(carriage.trainId, carriage.carriageIndex);
        }
        return entity.getUUID();
    }

    private static void renderBogeys(Snapshot snapshot, Viewport<?> viewport, Matrix4f transform) {
        long now = System.nanoTime();
        float amount = snapshot.bogeyReceivedNanos == 0 ? 1.0f : Math.max(0.0f, Math.min(1.25f,
                (float) (now - snapshot.bogeyReceivedNanos) / snapshot.bogeyIntervalNanos));
        int count = Math.min(snapshot.bogeyInfo.size(), snapshot.currentBogeys.size());
        Matrix4f model = new Matrix4f();
        for (int i = 0; i < count; i++) {
            var info = snapshot.bogeyInfo.get(i);
            DistantMesh mesh = DistantBogeyMeshes.get(info.styleId(), info.sizeId(), info.data());
            if (mesh == null) continue;
            var current = snapshot.currentBogeys.get(i);
            var previous = i < snapshot.previousBogeys.size() ? snapshot.previousBogeys.get(i) : current;
            double x = net.minecraft.util.Mth.lerp(amount, previous.x(), current.x());
            double y = net.minecraft.util.Mth.lerp(amount, previous.y(), current.y());
            double z = net.minecraft.util.Mth.lerp(amount, previous.z(), current.z());
            float yaw = previous.yaw() + net.minecraft.util.Mth.wrapDegrees(current.yaw() - previous.yaw()) * amount;
            float pitch = net.minecraft.util.Mth.lerp(amount, previous.pitch(), current.pitch());
            model.identity()
                    .translate((float) (x - viewport.cameraX), (float) (y - viewport.cameraY + 0.5),
                            (float) (z - viewport.cameraZ))
                    .rotateY((float) Math.toRadians(yaw))
                    .rotateX((float) Math.toRadians(pitch));
            if (current.upsideDown()) model.rotateZ((float) Math.PI);
            DistantBlockShader.uploadTransform(transform.set(viewport.MVP).mul(model));
            mesh.draw();
        }
    }

    private static DistantMesh.Built bake(AbstractContraptionEntity entity) {
        var contraption = entity.getContraption();
        var builder = new DistantMesh.Builder();
        var capture = new DistantVertexCapture(builder, true);
        var clientContraption = contraption.getOrCreateClientContraptionLazy();
        var renderWorld = clientContraption.getRenderLevel();
        var pose = new PoseStack();
        for (var layer : net.minecraft.client.renderer.RenderType.chunkBufferLayers()) {
            try {
                var buffer = com.simibubi.create.content.contraptions.render.ContraptionEntityRenderer
                        .getBuffer(contraption, renderWorld, layer);
                if (!buffer.isEmpty()) buffer.renderInto(pose, capture);
            } catch (Throwable ignored) {}
        }
        return builder.buildPersistent();
    }

    private static DistantMesh.Built bakeRemote(java.util.List<IntegratedContraptionTracker.RemoteBlock> blocks) {
        var builder = new DistantMesh.Builder();
        var shaper = Minecraft.getInstance().getModelManager().getBlockModelShaper();
        for (var block : blocks) {
            if (block.state().isAir() || block.state().getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL) continue;
            builder.blockModelLit(block.state(), shaper.getBlockModel(block.state()), BlockPos.ZERO,
                    block.x(), block.y(), block.z(), 0, 240);
        }
        return builder.buildPersistent();
    }

    private void save(net.minecraft.client.multiplayer.ClientLevel level, UUID id, Snapshot snapshot) {
        if (snapshot.meshBytes == null || snapshot.mesh == null) return;
        CreateSnapshotStore.saveContraption(level, id, snapshot.x, snapshot.y, snapshot.z,
                snapshot.signature, snapshot.train, snapshot.local, snapshot.light, snapshot.meshBytes);
    }

    private void restore(net.minecraft.client.multiplayer.ClientLevel level) {
        int restored = 0;
        for (var stored : CreateSnapshotStore.loadContraptions(level)) {
            DistantMesh mesh = DistantMesh.fromBytes(stored.mesh());
            if (mesh == null) continue;
            var snapshot = new Snapshot();
            snapshot.mesh = mesh;
            snapshot.meshBytes = stored.mesh();
            snapshot.x = stored.x();
            snapshot.y = stored.y();
            snapshot.z = stored.z();
            snapshot.signature = stored.signature();
            snapshot.train = stored.train();
            snapshot.local.set(stored.local());
            snapshot.light = stored.light();
            snapshot.lastSeen = System.currentTimeMillis();
            Snapshot old = this.snapshots.put(stored.id(), snapshot);
            if (old != null && old.mesh != null) old.mesh.free();
            restored++;
        }
        if (restored != 0) me.cortex.voxy.common.Logger.info("Restored " + restored + " Create contraption LOD snapshot(s)");
    }

    private static int sampleLight(net.minecraft.client.multiplayer.ClientLevel level, double x, double y, double z) {
        var pos = BlockPos.containing(x, y, z);
        if (!level.isLoaded(pos)) return net.minecraft.client.renderer.LightTexture.FULL_BRIGHT;
        int above = net.minecraft.client.renderer.LevelRenderer.getLightColor(level, pos.above());
        if (above != 0) return above;
        return net.minecraft.client.renderer.LevelRenderer.getLightColor(level, pos);
    }

    private static final class IntegratedContraptionTracker {
        private record Pose(ResourceKey<Level> dimension, double x, double y, double z,
                            byte axis, float angle, float yaw, float pitch, float initialYaw, float[] matrix,
                            boolean train, int light, List<BogeyPose> bogeys, long receivedNanos) {}
        private record RemoteBlock(int x, int y, int z, net.minecraft.world.level.block.state.BlockState state) {}
        private record BogeyInfo(ResourceLocation styleId, ResourceLocation sizeId, CompoundTag data) {}
        private record BogeyPose(double x, double y, double z, float yaw, float pitch, boolean upsideDown) {}

        private static final class Track {
            volatile Pose previous;
            volatile Pose current;
            volatile long intervalNanos = 250_000_000L;
            volatile long signature;
            volatile java.util.List<RemoteBlock> blocks;
            volatile List<BogeyInfo> bogeyInfo = List.of();
        }

        private static final java.util.concurrent.ConcurrentHashMap<UUID, Track> TRACKS =
                new java.util.concurrent.ConcurrentHashMap<>();
        private static final java.util.concurrent.atomic.AtomicBoolean PENDING =
                new java.util.concurrent.atomic.AtomicBoolean();
        private static int ticks;

        static void update(Minecraft mc, Map<UUID, Snapshot> snapshots, java.util.Set<UUID> seen) {
            var server = mc.getSingleplayerServer();
            if (server != null && ++ticks % 5 == 0 && PENDING.compareAndSet(false, true)) {
                UUID[] ids = snapshots.keySet().toArray(UUID[]::new);
                server.execute(() -> {
                    try {
                        long now = System.nanoTime();
                        for (UUID id : ids) {
                            for (var level : server.getAllLevels()) {
                                var entity = level.getEntity(id);
                                if (!(entity instanceof com.simibubi.create.content.contraptions.ControlledContraptionEntity controlled)) continue;
                                var axis = controlled.getRotationAxis();
                                if (axis == null) break;
                                Pose pose = new Pose(level.dimension(), controlled.getX(), controlled.getY(), controlled.getZ(),
                                        (byte) axis.ordinal(), controlled.getAngle(1.0f), 0, 0, 0, null, false,
                                        serverLight(level, controlled.blockPosition()), List.of(), now);
                                publish(id, pose, 0, null, null);
                                break;
                            }
                        }
                        var sampledCarriages = new java.util.HashSet<UUID>();
                        for (var level : server.getAllLevels()) {
                            var railways = com.simibubi.create.Create.RAILWAYS.sided(level);
                            for (var train : railways.trains.values()) {
                                for (int carriageIndex = 0; carriageIndex < train.carriages.size(); carriageIndex++) {
                                    var carriageData = train.carriages.get(carriageIndex);
                                    var dimensional = carriageData.getDimensionalIfPresent(level.dimension());
                                    if (dimensional == null || dimensional.positionAnchor == null) continue;
                                    UUID id = carriageId(train.id, carriageIndex);
                                    if (!sampledCarriages.add(id)) continue;
                                    Track known = TRACKS.get(id);
                                    var contraption = known == null || known.blocks == null
                                            ? resolveContraption(level, carriageData) : null;
                                    long signature = known == null ? 0 : known.signature;
                                    java.util.List<RemoteBlock> blocks = null;
                                    if (contraption != null && !contraption.getBlocks().isEmpty()) {
                                        signature = 1;
                                        for (var block : contraption.getBlocks().entrySet()) {
                                            signature = signature * 31 + block.getKey().asLong();
                                            signature = signature * 31 + block.getValue().state().hashCode();
                                        }
                                    }
                                    if (contraption != null && (known == null || known.signature != signature || known.blocks == null)) {
                                        var copied = new java.util.ArrayList<RemoteBlock>(
                                                Math.min(32768, contraption.getBlocks().size()));
                                        for (var block : contraption.getBlocks().entrySet()) {
                                            if (copied.size() >= 32768) break;
                                            var pos = block.getKey();
                                            copied.add(new RemoteBlock(pos.getX(), pos.getY(), pos.getZ(), block.getValue().state()));
                                        }
                                        blocks = java.util.List.copyOf(copied);
                                    }
                                    var leading = dimensional.rotationAnchors.getFirst();
                                    var trailing = dimensional.rotationAnchors.getSecond();
                                    float yaw = 0;
                                    float pitch = 0;
                                    if (leading != null && trailing != null) {
                                        var diff = leading.subtract(trailing);
                                        yaw = (float) (Math.atan2(diff.z, diff.x) * (180.0 / Math.PI)) + 180.0f;
                                        pitch = (float) (-Math.atan2(diff.y,
                                                Math.sqrt(diff.x * diff.x + diff.z * diff.z)) * (180.0 / Math.PI));
                                    }
                                    List<BogeyInfo> info = bogeyInfo(carriageData);
                                    List<BogeyPose> bogeys = bogeyPoses(train, carriageData, level.dimension());
                                    var anchor = dimensional.positionAnchor;
                                    Pose pose = new Pose(level.dimension(), anchor.x, anchor.y, anchor.z,
                                            (byte) -1, 0.0f, yaw, pitch, initialYaw(carriageData), null, true,
                                            serverLight(level, BlockPos.containing(anchor)), bogeys, now);
                                    publish(id, pose, signature, blocks, info);
                                }
                            }
                        }
                    } finally {
                        PENDING.set(false);
                    }
                });
            }

            long now = System.nanoTime();
            long nowMs = System.currentTimeMillis();
            TRACKS.entrySet().removeIf(entry -> {
                Track track = entry.getValue();
                Pose current = track.current;
                if (current == null || now - current.receivedNanos() > 1_500_000_000L) return true;
                if (!mc.level.dimension().equals(current.dimension())) return false;
                Snapshot snapshot = snapshots.get(entry.getKey());
                if (snapshot == null) {
                    snapshot = new Snapshot();
                    snapshots.put(entry.getKey(), snapshot);
                }
                if (current.train()) {
                    snapshot.remoteTrack = track;
                    snapshot.bogeyInfo = track.bogeyInfo;
                    snapshot.previousBogeys = track.previous == null ? current.bogeys() : track.previous.bogeys();
                    snapshot.currentBogeys = current.bogeys();
                    snapshot.bogeyReceivedNanos = current.receivedNanos();
                    snapshot.bogeyIntervalNanos = track.intervalNanos;
                }
                if (seen.contains(entry.getKey())) return false;
                boolean meshChanged = false;
                if (current.train() && track.blocks != null
                        && (snapshot.mesh == null || snapshot.signature != track.signature)) {
                    DistantMesh.Built built = bakeRemote(track.blocks);
                    if (built.mesh() != null) {
                        if (snapshot.mesh != null) snapshot.mesh.free();
                        snapshot.mesh = built.mesh();
                        snapshot.meshBytes = built.bytes();
                        snapshot.signature = track.signature;
                        snapshot.train = true;
                        meshChanged = true;
                    }
                }
                Pose previous = track.previous == null ? current : track.previous;
                float amount = Math.max(0.0f, Math.min(1.0f,
                        (float) (now - current.receivedNanos()) / track.intervalNanos));
                snapshot.x = net.minecraft.util.Mth.lerp(amount, previous.x(), current.x());
                snapshot.y = net.minecraft.util.Mth.lerp(amount, previous.y(), current.y());
                snapshot.z = net.minecraft.util.Mth.lerp(amount, previous.z(), current.z());
                if (current.train()) {
                    float yaw = previous.yaw() + net.minecraft.util.Mth.wrapDegrees(
                            current.yaw() - previous.yaw()) * amount;
                    float pitch = net.minecraft.util.Mth.lerp(amount, previous.pitch(), current.pitch());
                    snapshot.local.identity().translate(0.0f, 0.5f, 0.0f)
                            .rotateY((float) Math.toRadians(-yaw))
                            .rotateZ((float) Math.toRadians(pitch))
                            .rotateY((float) Math.toRadians(current.initialYaw()))
                            .translate(-0.5f, -0.5f, -0.5f);
                } else if (current.matrix() != null) {
                    snapshot.local.set(current.matrix());
                } else {
                    float angle = previous.angle() + net.minecraft.util.Mth.wrapDegrees(
                            current.angle() - previous.angle()) * amount;
                    snapshot.local.identity().translate(0.5f, 0.5f, 0.5f);
                    switch (current.axis()) {
                        case 0 -> snapshot.local.rotateX((float) Math.toRadians(angle));
                        case 1 -> snapshot.local.rotateY((float) Math.toRadians(angle));
                        default -> snapshot.local.rotateZ((float) Math.toRadians(angle));
                    }
                    snapshot.local.translate(-0.5f, -0.5f, -0.5f);
                }
                snapshot.train = current.train();
                snapshot.light = current.light();
                snapshot.lastSeen = nowMs;
                if (meshChanged) {
                    CreateSnapshotStore.saveContraption(mc.level, entry.getKey(), snapshot.x, snapshot.y, snapshot.z,
                            snapshot.signature, true, snapshot.local, snapshot.light, snapshot.meshBytes);
                }
                return false;
            });
        }

        private static void publish(UUID id, Pose pose, long signature, java.util.List<RemoteBlock> blocks,
                                    List<BogeyInfo> bogeyInfo) {
            TRACKS.compute(id, (ignored, track) -> {
                if (track == null) track = new Track();
                if (track.current != null) {
                    track.previous = track.current;
                    track.intervalNanos = Math.max(50_000_000L,
                            Math.min(1_000_000_000L, pose.receivedNanos() - track.current.receivedNanos()));
                } else {
                    track.previous = pose;
                }
                track.current = pose;
                if (blocks != null && signature != track.signature) {
                    track.signature = signature;
                    track.blocks = blocks;
                }
                if (bogeyInfo != null) track.bogeyInfo = bogeyInfo;
                return track;
            });
        }

        static UUID carriageId(UUID trainId, int carriageIndex) {
            return UUID.nameUUIDFromBytes((trainId + ":" + carriageIndex)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        private static List<BogeyInfo> bogeyInfo(com.simibubi.create.content.trains.entity.Carriage carriage) {
            var result = new java.util.ArrayList<BogeyInfo>(2);
            addBogeyInfo(carriage.bogeys.getFirst(), result);
            if (carriage.isOnTwoBogeys()) addBogeyInfo(carriage.bogeys.getSecond(), result);
            return List.copyOf(result);
        }

        private static void addBogeyInfo(com.simibubi.create.content.trains.entity.CarriageBogey bogey,
                                         java.util.List<BogeyInfo> result) {
            var style = bogey.getStyle();
            var size = bogey.getSize();
            result.add(new BogeyInfo(style.id, size.id(),
                    bogey.bogeyData == null ? new CompoundTag() : bogey.bogeyData.copy()));
        }

        private static List<BogeyPose> bogeyPoses(com.simibubi.create.content.trains.entity.Train train,
                                                   com.simibubi.create.content.trains.entity.Carriage carriage,
                                                   ResourceKey<Level> dimension) {
            if (train.derailed || train.graph == null) return List.of();
            var result = new java.util.ArrayList<BogeyPose>(2);
            if (!addBogeyPose(train, carriage.bogeys.getFirst(), dimension, result)) return List.of();
            if (carriage.isOnTwoBogeys()
                    && !addBogeyPose(train, carriage.bogeys.getSecond(), dimension, result)) return List.of();
            return List.copyOf(result);
        }

        private static boolean addBogeyPose(com.simibubi.create.content.trains.entity.Train train,
                                             com.simibubi.create.content.trains.entity.CarriageBogey bogey,
                                             ResourceKey<Level> dimension, java.util.List<BogeyPose> result) {
            var leading = bogey.leading();
            var trailing = bogey.trailing();
            if (leading.edge == null || trailing.edge == null || !dimension.equals(bogey.getDimension())) return false;
            var first = leading.getPosition(train.graph);
            var second = trailing.getPosition(train.graph);
            var anchor = first.add(second).scale(0.5);
            double dx = first.x - second.x;
            double dy = first.y - second.y;
            double dz = first.z - second.z;
            float yRot = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) + 90.0f;
            float xRot = (float) (Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * (180.0 / Math.PI));
            result.add(new BogeyPose(anchor.x, anchor.y, anchor.z, -yRot, xRot, bogey.isUpsideDown()));
            return true;
        }

        private static java.lang.reflect.Field serializedEntity;

        private static com.simibubi.create.content.contraptions.Contraption resolveContraption(
                net.minecraft.server.level.ServerLevel level,
                com.simibubi.create.content.trains.entity.Carriage carriage) {
            try {
                var entity = carriage.anyAvailableEntity();
                if (entity != null && entity.getContraption() != null) return entity.getContraption();
                CompoundTag serialized = serializedEntity(carriage);
                if (serialized == null || !serialized.contains("Contraption")) return null;
                return com.simibubi.create.content.contraptions.Contraption.fromNBT(
                        level, serialized.getCompound("Contraption"), false);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static float initialYaw(com.simibubi.create.content.trains.entity.Carriage carriage) {
            try {
                var entity = carriage.anyAvailableEntity();
                if (entity != null) return entity.getInitialYaw();
                CompoundTag serialized = serializedEntity(carriage);
                if (serialized != null && serialized.contains("InitialOrientation")) {
                    return net.minecraft.core.Direction.valueOf(serialized.getString("InitialOrientation")
                            .toUpperCase(java.util.Locale.ROOT)).toYRot();
                }
            } catch (Throwable ignored) {}
            return 0;
        }

        private static CompoundTag serializedEntity(com.simibubi.create.content.trains.entity.Carriage carriage)
                throws ReflectiveOperationException {
            if (serializedEntity == null) {
                serializedEntity = com.simibubi.create.content.trains.entity.Carriage.class
                        .getDeclaredField("serialisedEntity");
                serializedEntity.setAccessible(true);
            }
            return (CompoundTag) serializedEntity.get(carriage);
        }

        private static int serverLight(net.minecraft.server.level.ServerLevel level, BlockPos pos) {
            BlockPos sample = pos.above();
            int block = level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, sample);
            int sky = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, sample);
            return block << 4 | sky << 20;
        }

        static void clear() {
            TRACKS.clear();
            ticks = 0;
        }
    }

    private void clear() {
        IntegratedContraptionTracker.clear();
        for (Snapshot snapshot : this.snapshots.values()) {
            if (snapshot.mesh != null) snapshot.mesh.free();
        }
        this.snapshots.clear();
    }
}

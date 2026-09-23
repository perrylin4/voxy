package me.cortex.voxy.client.compat.create;

import com.simibubi.create.AllPartialModels;
import com.simibubi.create.CreateClient;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackShape;
import me.cortex.voxy.client.compat.LodPipelineHooks;
import me.cortex.voxy.client.compat.distant.DistantBlockShader;
import me.cortex.voxy.client.compat.distant.DistantMesh;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;

public final class DistantTrackRenderer implements LodPipelineHooks.Renderer {
    public static final DistantTrackRenderer INSTANCE = new DistantTrackRenderer();
    private static final long RECHECK_MS = 5000;
    private final List<MeshUnit> units = new ArrayList<>();
    private ResourceKey<Level> dimension;
    private long lastCheck;
    private long checksum;
    private final java.util.Set<BlockPos> replacementBlocks = new java.util.HashSet<>();

    private record MeshUnit(DistantMesh mesh, double x, double y, double z) {}
    private record Turn(TrackGraph graph, TrackEdge edge,
                        com.simibubi.create.content.trains.track.BezierConnection curve) {}
    private record Straight(TrackNode node, TrackNode other, TrackEdge edge) {}
    private record TrackBlockAt(BlockPos pos, BlockState state) {}

    private DistantTrackRenderer() {}

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null || !VoxyConfig.CONFIG.isRenderingEnabled() || !VoxyConfig.CONFIG.distantTracks) {
            this.clear();
            return;
        }
        long now = System.currentTimeMillis();
        boolean changedDimension = !mc.level.dimension().equals(this.dimension);
        if (!changedDimension && now - this.lastCheck < RECHECK_MS) return;
        this.lastCheck = now;
        long next = 1;
        try {
            for (TrackGraph graph : CreateClient.RAILWAYS.trackNetworks.values()) {
                next = next * 31 + graph.getChecksum();
            }
        } catch (Throwable ignored) {
            return;
        }
        if (!changedDimension && next == this.checksum) return;
        this.dimension = mc.level.dimension();
        this.checksum = next;
        try {
            this.rebuild(mc);
        } catch (Throwable t) {
            this.clear();
            Logger.error("Distant Create track bake failed", t);
        }
    }

    @SubscribeEvent
    public void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        this.clear();
    }

    @Override
    public void render(AbstractRenderPipeline pipeline, Viewport<?> viewport, int depthFunc) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().equals(this.dimension)
                || !VoxyConfig.CONFIG.distantTracks || this.units.isEmpty()) return;
        double min = Math.max(16.0, mc.options.getEffectiveRenderDistance() * 16.0);
        double minSq = min * min;
        double max = VoxyConfig.CONFIG.distantTrackMaxChunks == 0
                ? VoxyConfig.CONFIG.getLodRenderDistanceBlocks()
                : VoxyConfig.CONFIG.distantTrackMaxChunks * 16.0;
        double maxSq = max * max;
        boolean bound = false;
        Matrix4f transform = new Matrix4f();
        pipeline.setupAndBindOpaque(viewport);
        for (MeshUnit unit : this.units) {
            double dx = unit.x - viewport.cameraX;
            double dy = unit.y - viewport.cameraY;
            double dz = unit.z - viewport.cameraZ;
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance < minSq || distance > maxSq) continue;
            if (!bound) {
                DistantBlockShader.get(pipeline).bind();
                DistantBlockShader.bindTextures();
                glEnable(GL_DEPTH_TEST);
                glDepthFunc(depthFunc);
                glDepthMask(true);
                glDisable(GL_CULL_FACE);
                glDisable(GL_BLEND);
                glEnable(GL_STENCIL_TEST);
                glStencilFunc(GL_EQUAL, 1, 0x1);
                glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
                bound = true;
            }
            transform.set(viewport.MVP).translate((float) dx, (float) dy, (float) dz);
            DistantBlockShader.uploadTransform(transform);
            unit.mesh.draw();
        }
        if (bound) {
            glBindVertexArray(0);
            glUseProgram(0);
            glStencilFunc(GL_EQUAL, 1, 0xFF);
            glStencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
        }
    }

    private void rebuild(Minecraft mc) {
        me.cortex.voxy.client.compat.distant.TrackLodReplacement.clear();
        this.replacementBlocks.clear();
        for (MeshUnit unit : this.units) unit.mesh.free();
        this.units.clear();
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        List<Turn> turns = new ArrayList<>();
        List<Straight> straights = new ArrayList<>();
        for (TrackGraph graph : new ArrayList<>(CreateClient.RAILWAYS.trackNetworks.values())) {
            for (TrackNodeLocation location : new ArrayList<>(graph.getNodes())) {
                if (!this.dimension.equals(location.getDimension())) continue;
                TrackNode node = graph.locateNode(location);
                if (node == null) continue;
                for (var connection : graph.getConnectionsFrom(node).entrySet()) {
                    TrackNode other = connection.getKey();
                    if (!this.dimension.equals(other.getLocation().getDimension())) continue;
                    TrackEdge edge = connection.getValue();
                    if (edge.isTurn()) {
                        var curve = edge.getTurn();
                        if (curve != null && curve.isPrimary()) turns.add(new Turn(graph, edge, curve));
                    } else if (node.getNetId() < other.getNetId()) {
                        straights.add(new Straight(node, other, edge));
                    }
                }
            }
        }
        for (Turn turn : turns) {
            putAnchor(mc, turn.curve.bePositions.getFirst(), blocks);
            putAnchor(mc, turn.curve.bePositions.getSecond(), blocks);
        }
        var excluded = new java.util.HashSet<BlockPos>();
        for (Turn turn : turns) collectCurveBlocks(turn, excluded);
        for (Straight straight : straights) collectStraight(straight, blocks, excluded);
        Map<Long, List<TrackBlockAt>> sections = new HashMap<>();
        for (var entry : blocks.entrySet()) {
            BlockPos pos = entry.getKey();
            long key = BlockPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
            sections.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new TrackBlockAt(pos, entry.getValue()));
        }
        for (var entry : sections.entrySet()) bakeSection(mc, entry.getKey(), entry.getValue());
        for (Turn turn : turns) bakeTurn(mc, turn.curve);
        me.cortex.voxy.client.compat.distant.TrackLodReplacement.upload(this.replacementBlocks);
    }

    private static void putAnchor(Minecraft mc, BlockPos pos, Map<BlockPos, BlockState> blocks) {
        BlockState state = mc.level.getBlockState(pos);
        if (state.getBlock() instanceof TrackBlock) blocks.put(pos, state);
    }

    private static void collectCurveBlocks(Turn turn, java.util.Set<BlockPos> out) {
        double length = turn.edge.getLength();
        if (length < 0.5) return;
        double reach = Math.min(1.2, length / 2);
        for (double d = 0.4; d <= reach; d += 0.4) {
            out.add(BlockPos.containing(turn.edge.getPosition(turn.graph, d / length)));
            out.add(BlockPos.containing(turn.edge.getPosition(turn.graph, 1.0 - d / length)));
        }
    }

    private static void collectStraight(Straight straight, Map<BlockPos, BlockState> blocks,
                                        java.util.Set<BlockPos> excluded) {
        Vec3 from = straight.node.getLocation().getLocation();
        Vec3 to = straight.other.getLocation().getLocation();
        Vec3 diff = to.subtract(from);
        double length = diff.length();
        if (length < 0.05) return;
        BlockState state;
        try {
            state = straight.edge.getTrackMaterial().getBlock().defaultBlockState()
                    .setValue(TrackBlock.SHAPE, shape(diff));
        } catch (Throwable ignored) {
            return;
        }
        var visited = new LinkedHashSet<BlockPos>();
        int steps = Math.max(1, (int) Math.ceil(length / 0.4));
        for (int i = 0; i <= steps; i++) visited.add(BlockPos.containing(from.add(diff.scale((double) i / steps))));
        boolean placed = false;
        for (BlockPos pos : visited) {
            if (!excluded.contains(pos)) {
                blocks.putIfAbsent(pos, state);
                placed = true;
            }
        }
        if (!placed) for (BlockPos pos : visited) blocks.putIfAbsent(pos, state);
    }

    private static TrackShape shape(Vec3 diff) {
        if (Math.abs(diff.y) > 0.25) {
            Vec3 ascent = diff.y > 0 ? diff : diff.scale(-1);
            if (Math.abs(ascent.x) > Math.abs(ascent.z)) return ascent.x > 0 ? TrackShape.AE : TrackShape.AW;
            return ascent.z > 0 ? TrackShape.AS : TrackShape.AN;
        }
        double x = Math.abs(diff.x), z = Math.abs(diff.z);
        if (x < z * 0.1) return TrackShape.ZO;
        if (z < x * 0.1) return TrackShape.XO;
        return (diff.x > 0) == (diff.z > 0) ? TrackShape.PD : TrackShape.ND;
    }

    private void bakeSection(Minecraft mc, long key, List<TrackBlockAt> blocks) {
        int sx = BlockPos.getX(key) << 4, sy = BlockPos.getY(key) << 4, sz = BlockPos.getZ(key) << 4;
        var builder = new DistantMesh.Builder();
        for (TrackBlockAt entry : blocks) {
            int light = LevelRenderer.getLightColor(mc.level, entry.pos.above());
            builder.blockModelLit(entry.state, mc.getBlockRenderer().getBlockModel(entry.state), entry.pos,
                    entry.pos.getX() - sx, entry.pos.getY() - sy, entry.pos.getZ() - sz,
                    light & 0xFFFF, light >>> 16 & 0xFFFF);
        }
        DistantMesh mesh = builder.build();
        if (mesh != null) {
            this.units.add(new MeshUnit(mesh, sx, sy, sz));
            for (TrackBlockAt entry : blocks) this.replacementBlocks.add(entry.pos);
        }
    }

    private void bakeTurn(Minecraft mc, com.simibubi.create.content.trains.track.BezierConnection curve) {
        BlockPos anchor = curve.bePositions.getFirst();
        BlockPos far = curve.bePositions.getSecond();
        int lightA = LevelRenderer.getLightColor(mc.level, anchor.above());
        int lightB = LevelRenderer.getLightColor(mc.level, far.above());
        var builder = new DistantMesh.Builder();
        try {
            var segments = curve.getBakedSegments();
            var holder = curve.getMaterial().getModelHolder();
            for (int i = 1; i < segments.length; i++) {
                float t = (float) i / (segments.length - 1);
                int block = Math.round(net.minecraft.util.Mth.lerp(t, lightA & 0xFFFF, lightB & 0xFFFF));
                int sky = Math.round(net.minecraft.util.Mth.lerp(t, lightA >>> 16 & 0xFFFF, lightB >>> 16 & 0xFFFF));
                builder.transformedModel(holder.tie().get(), segments.tieTransform[i].pose(), block, sky);
                builder.transformedModel(holder.leftSegment().get(), segments.railTransforms[i].getFirst().pose(), block, sky);
                builder.transformedModel(holder.rightSegment().get(), segments.railTransforms[i].getSecond().pose(), block, sky);
            }
            if (curve.hasGirder) {
                var girders = curve.getBakedGirders();
                for (int i = 1; i < girders.length; i++) {
                    float t = (float) i / (girders.length - 1);
                    int block = Math.round(net.minecraft.util.Mth.lerp(t, lightA & 0xFFFF, lightB & 0xFFFF));
                    int sky = Math.round(net.minecraft.util.Mth.lerp(t, lightA >>> 16 & 0xFFFF, lightB >>> 16 & 0xFFFF));
                    for (boolean first : new boolean[]{true, false}) {
                        builder.transformedModel(AllPartialModels.GIRDER_SEGMENT_MIDDLE.get(), girders.beams[i].get(first).pose(), block, sky);
                        for (boolean top : new boolean[]{true, false}) {
                            builder.transformedModel((top ? AllPartialModels.GIRDER_SEGMENT_TOP
                                    : AllPartialModels.GIRDER_SEGMENT_BOTTOM).get(),
                                    girders.beamCaps[i].get(top).get(first).pose(), block, sky);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Logger.error("Distant curved track bake failed at " + anchor, t);
            DistantMesh partial = builder.build();
            if (partial != null) partial.free();
            return;
        }
        DistantMesh mesh = builder.build();
        if (mesh != null) this.units.add(new MeshUnit(mesh, anchor.getX(), anchor.getY(), anchor.getZ()));
    }

    private void clear() {
        me.cortex.voxy.client.compat.distant.TrackLodReplacement.clear();
        this.replacementBlocks.clear();
        for (MeshUnit unit : this.units) unit.mesh.free();
        this.units.clear();
        this.dimension = null;
        this.checksum = 0;
        this.lastCheck = 0;
    }
}

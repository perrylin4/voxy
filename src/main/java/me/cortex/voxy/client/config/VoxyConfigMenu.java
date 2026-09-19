package me.cortex.voxy.client.config;

import me.cortex.voxy.client.ClientSessionEvents;
import me.cortex.voxy.client.config.SodiumConfigBuilder.*;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.SSAO;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.iris.LiteShaderStatus;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.compat.far.FarEntityClient;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPointForge;
import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.option.Range;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

@ConfigEntryPointForge("voxy")
public class VoxyConfigMenu implements ConfigEntryPoint {
    @Override
    public void registerConfigLate(ConfigBuilder B) {
        if (!VoxyCommon.isAvailable()) return;

        var CFG = VoxyConfig.CONFIG;
        boolean sableInstalled = ModList.get().isLoaded("sable");
        boolean createInstalled = ModList.get().isLoaded("create");
        boolean powerGridInstalled = ModList.get().isLoaded("powergrid");
        boolean copycatsInstalled = ModList.get().isLoaded("copycats");
        boolean simulatedInstalled = ModList.get().isLoaded("simulated");
        boolean framedBlocksInstalled = ModList.get().isLoaded("framedblocks");
        boolean littleTilesInstalled = ModList.get().isLoaded("littletiles");
        boolean domumInstalled = ModList.get().isLoaded("domum_ornamentum");
        boolean seasonsInstalled = me.cortex.voxy.client.core.compat.eclipticseasons.EsCompatGate.shouldArm();

        var cc = B.registerModOptions("voxy", VoxyCommon.displayName(), VoxyCommon.MOD_VERSION)
                .setIcon(ResourceLocation.parse("voxy:icon.png"));

        final var RENDER_RELOAD = OptionFlag.REQUIRES_RENDERER_RELOAD.getId().toString();

        SodiumConfigBuilder.buildToSodium(B, cc, CFG::save, postOp->{
                    postOp.register("voxy:update_threads", ()->{
                        var instance = VoxyCommon.getInstance();
                        if (instance != null) {
                            instance.updateDedicatedThreads();
                        }
                    }, "voxy:enabled")
                            .register("voxy:iris_reload", IrisUtil::reload)
                            .register("voxy:refresh_far_entities", FarEntityClient::sendHello)
                            .register("voxy:refresh_chunk_request", ()->{
                                var minecraft = Minecraft.getInstance();
                                if (minecraft.getConnection() != null) {
                                    minecraft.options.broadcastOptions();
                                }
                            });
                },
                new Page(Component.translatable("voxy.config.general"),
                        new Group(Component.translatable("voxy.config.group.general"),
                                new BoolOption(
                                        "voxy:enabled",
                                        Component.translatable("voxy.config.general.enabled"),
                                        ()->CFG.enabled, v->{
                                            CFG.enabled=v;
                                            if (v && ClientSessionEvents.inSession) {
                                                VoxyCommon.createInstance();
                                            }
                                        })
                                        .setPostChangeRunner(c->{
                                            if (!c) {
                                                var vrsh = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;
                                                if (vrsh != null) {
                                                    vrsh.voxy$shutdownRenderer();
                                                }
                                                VoxyCommon.shutdownInstance();
                                            }
                                        }).setPostChangeFlags(RENDER_RELOAD, "voxy:iris_reload").setEnabler(null)
                        ), new Group(Component.translatable("voxy.config.group.threads"),
                                new IntOption(
                                        "voxy:thread_count",
                                        Component.translatable("voxy.config.general.serviceThreads"),
                                        ()->CFG.serviceThreads, v->CFG.serviceThreads=v,
                                        new Range(1, CpuLayout.getCoreCount(), 1))
                                        .setPostChangeFlags("voxy:update_threads"),
                                new BoolOption(
                                        "voxy:use_sodium_threads",
                                        Component.translatable("voxy.config.general.useSodiumBuilder"),
                                        ()->!CFG.dontUseSodiumBuilderThreads, v->CFG.dontUseSodiumBuilderThreads=!v)
                                        .setPostChangeFlags("voxy:update_threads", RENDER_RELOAD)
                        ), new Group(Component.translatable("voxy.config.group.data"),
                                new BoolOption(
                                        "voxy:ingest_enabled",
                                        Component.translatable("voxy.config.general.ingest"),
                                        ()->CFG.ingestEnabled, v->CFG.ingestEnabled=v),
                                new BoolOption(
                                        "voxy:show_join_message",
                                        Component.translatable("voxy.config.general.showJoinMessage"),
                                        ()->CFG.showJoinMessage, v->CFG.showJoinMessage=v)
                        )
                ).setEnabler("voxy:enabled"),
                new Page(Component.translatable("voxy.config.rendering"),
                        new Group(Component.translatable("voxy.config.group.activation"),
                                new BoolOption(
                                        "voxy:rendering",
                                        Component.translatable("voxy.config.general.rendering"),
                                        ()->CFG.enableRendering, v->CFG.enableRendering=v)
                                        .setPostChangeRunner(c->{
                                            var vrsh = (IGetVoxyRenderSystem)Minecraft.getInstance().levelRenderer;
                                            if (vrsh != null) {
                                                if (c) {
                                                    vrsh.voxy$createRenderer();
                                                } else {
                                                    vrsh.voxy$shutdownRenderer();
                                                }
                                            }
                                        },"voxy:enabled", RENDER_RELOAD)
                                        .setPostChangeFlags("voxy:iris_reload")
                                        .setEnabler("voxy:enabled")
                        ), new Group(Component.translatable("voxy.config.group.quality"),
                                new IntOption(
                                        "voxy:subdivsize",
                                        Component.translatable("voxy.config.general.subDivisionSize"),
                                        CFG::getRenderQualityLevel, CFG::setRenderQualityLevel,
                                        new Range(0, 6, 1))
                                        .setFormatter(v->Component.translatable("voxy.config.general.renderQuality." + v))
                                        .setDefault(VoxyConfig.DEFAULT_RENDER_QUALITY_LEVEL)
                                        .setImpact(OptionImpact.HIGH),
                                new IntOption(
                                        "voxy:render_distance",
                                        Component.translatable("voxy.config.general.renderDistance"),
                                        ()->Math.round(CFG.sectionRenderDistance*16), v->CFG.sectionRenderDistance=((float)v)/16,
                                        new Range(10, 64*16, 1))
                                        .setFormatter(v->Component.literal(Integer.toString(v*2)))
                                        .setPostChangeRunner(c->{
                                            var vrsh = (IGetVoxyRenderSystem)Minecraft.getInstance().levelRenderer;
                                            if (vrsh != null) {
                                                var vrs = vrsh.voxy$getRenderSystem();
                                                if (vrs != null) {
                                                    vrs.setRenderDistance(CFG.sectionRenderDistance);
                                                }
                                            }
                                        }, "voxy:rendering", RENDER_RELOAD)
                                        .setImpact(OptionImpact.MEDIUM),
                                new IntOption(
                                        "voxy:render_pressure",
                                        Component.translatable("voxy.config.general.renderPressure"),
                                        ()->CFG.getRenderPressureLevel(), v->CFG.renderPressure=v,
                                        new Range(0, 4, 1))
                                        .setFormatter(v->Component.translatable("voxy.config.general.renderPressure." + v))
                                        .setImpact(OptionImpact.HIGH),
                                new IntOption(
                                        "voxy:leaf_lod_mode",
                                        Component.translatable("voxy.config.general.leafLodMode"),
                                        ()->CFG.getLeafLodMode().ordinal(),
                                        v->CFG.setLeafLodMode(VoxyConfig.LeafLodMode.values()[v]),
                                        new Range(0, 2, 1))
                                        .setFormatter(v->Component.translatable("voxy.config.general.leafLodMode."
                                                + VoxyConfig.LeafLodMode.values()[v].name().toLowerCase(java.util.Locale.ROOT)))
                                        .setDefault(VoxyConfig.LeafLodMode.BALANCED.ordinal())
                                        .setPostChangeFlags(RENDER_RELOAD)
                                        .setImpact(OptionImpact.MEDIUM),
                                new IntOption(
                                        "voxy:earth_curve_ratio",
                                        Component.translatable("voxy.config.general.earthCurveRatio"),
                                        ()->CFG.earthCurveRatio, v->CFG.earthCurveRatio=(v > 0 && v < 50) ? 50 : v,
                                        new Range(0, 10000, 50))
                                        .setPostChangeFlags(RENDER_RELOAD)
                                        .setImpact(OptionImpact.LOW)
                        ), new Group(Component.translatable("voxy.config.group.vanillaEffects"),
                                new BoolOption(
                                    "voxy:environmental_fog",
                                    Component.translatable("voxy.config.general.environmental_fog"),
                                    () -> CFG.useEnvironmentalFog,
                                    v -> CFG.useEnvironmentalFog = v),
                                new EnumOption<>("voxy:ssao_mode",
                                        SSAO.SSAOMode.class,
                                        Component.translatable("voxy.config.general.ssao_mode"),
                                        ()->CFG.getSSAOMode(), v->CFG.setSSAOMode(v))
                                        .setImpact(OptionImpact.MEDIUM)
                                        .setPostChangeFlags(RENDER_RELOAD)
                        )
                        .setEnablerInherit(s->!IrisUtil.irisShaderPackEnabled(), ConfigState.UPDATE_ON_REBUILD),
                        new Group(Component.translatable("voxy.config.group.clouds"),
                                new BoolOption(
                                        "voxy:adapt_cloud_distance",
                                        Component.translatable("voxy.config.general.adaptCloudDistance"),
                                        ()->CFG.adaptCloudDistance, v->CFG.adaptCloudDistance=v),
                                new IntOption(
                                        "voxy:cloud_distance",
                                        Component.translatable("voxy.config.general.cloudDistance"),
                                        ()->CFG.cloudDistance, v->CFG.cloudDistance=v,
                                        new Range(0, VoxyConfig.MAX_CLOUD_DISTANCE, 1))
                                        .setImpact(OptionImpact.LOW)
                        )
                        .setEnablerInherit(s->!IrisUtil.irisShaderPackEnabled(), ConfigState.UPDATE_ON_REBUILD),
                        new Group(Component.translatable("voxy.config.group.fog"),
                                new IntOption(
                                        "voxy:fog_intensity",
                                        Component.translatable("voxy.config.general.fogIntensity"),
                                        ()->Math.round(CFG.fogIntensity * 100), v->CFG.fogIntensity=v / 100.0f,
                                        new Range(0, 100, 1))
                                        .setImpact(OptionImpact.LOW),
                                new IntOption(
                                        "voxy:fog_density",
                                        Component.translatable("voxy.config.general.fogDensity"),
                                        ()->Math.round(CFG.fogDensity * 100), v->CFG.fogDensity=v / 100.0f,
                                        new Range(0, 100, 1))
                                        .setImpact(OptionImpact.LOW),
                                new IntOption(
                                        "voxy:sky_fog_distance",
                                        Component.translatable("voxy.config.general.skyFogDistance"),
                                        ()->CFG.skyFogDistance, v->CFG.skyFogDistance=v,
                                        new Range(0, 1024, 1))
                                        .setImpact(OptionImpact.LOW)
                                        .setPostChangeFlags(RENDER_RELOAD),
                                new IntOption(
                                        "voxy:fog_distance",
                                        Component.translatable("voxy.config.general.fog_distance"),
                                        ()->CFG.fogDistancePercent, v->CFG.fogDistancePercent=v,
                                        new Range(5, 200, 5))
                                        .setFormatter(v->Component.literal(v+"%"))
                                        .setImpact(OptionImpact.LOW)
                        )
                        .setEnablerInherit(s->!IrisUtil.irisShaderPackEnabled(), ConfigState.UPDATE_ON_REBUILD),
                        new Group(Component.translatable("voxy.config.group.biomeColours"),
                                new IntOption(
                                        "voxy:biome_blend_radius",
                                        Component.translatable("voxy.config.general.biomeBlendRadius"),
                                        ()->CFG.biomeBlendRadius, v->CFG.biomeBlendRadius=v,
                                        new Range(0, 7, 1))
                                        .setFormatter(v->v == 0
                                                ? Component.translatable("voxy.config.general.biomeBlendRadius.off")
                                                : Component.literal(Integer.toString(v)))
                                        .setPostChangeFlags(RENDER_RELOAD)
                                        .setImpact(OptionImpact.MEDIUM),
                                new BoolOption(
                                        "voxy:biome_blend_grass",
                                        Component.translatable("voxy.config.general.biomeBlendGrass"),
                                        ()->"water_grass".equals(CFG.biomeBlendScope),
                                        v->CFG.biomeBlendScope=v ? "water_grass" : "water")
                                        .setPostChangeFlags(RENDER_RELOAD)
                                        .setImpact(OptionImpact.MEDIUM)
                        ),
                        new Group(Component.translatable("voxy.config.farEntities"),
                                new BoolOption(
                                        "voxy:far_players",
                                        Component.translatable("voxy.config.farEntities.players"),
                                        ()->CFG.enableFarPlayerRendering, v->CFG.enableFarPlayerRendering=v)
                                        .setPostChangeFlags("voxy:refresh_far_entities"),
                                new BoolOption(
                                        "voxy:far_vehicles",
                                        Component.translatable("voxy.config.farEntities.vehicles"),
                                        ()->CFG.enableFarVehicleRendering, v->CFG.enableFarVehicleRendering=v)
                                        .setPostChangeFlags("voxy:refresh_far_entities"),
                                new BoolOption(
                                        "voxy:far_player_names",
                                        Component.translatable("voxy.config.farEntities.names"),
                                        ()->CFG.renderFarPlayerNames, v->CFG.renderFarPlayerNames=v)
                                        .setEnablerInherit("voxy:far_players"),
                                new IntOption(
                                        "voxy:far_player_animation_distance",
                                        Component.translatable("voxy.config.farEntities.animationDistance"),
                                        ()->CFG.farPlayerAnimationDistance, v->CFG.farPlayerAnimationDistance=v,
                                        new Range(0, 32768, 64))
                                        .setFormatter(v->v == 0
                                                ? Component.translatable("voxy.config.compat.distanceFollowLod")
                                                : Component.translatable("voxy.config.unit.blocks", v))
                                        .setEnablerInherit("voxy:far_players")
                                        .setImpact(OptionImpact.LOW),
                                new BoolOption(
                                        "voxy:share_far_player_position",
                                        Component.translatable("voxy.config.farEntities.sharePosition"),
                                        ()->CFG.shareFarPlayerPosition, v->CFG.shareFarPlayerPosition=v)
                                        .setPostChangeFlags("voxy:refresh_far_entities")
                        ).setEnablerInherit(s->me.cortex.voxy.client.ServerCapabilities.canConfigureFarEntities(), ConfigState.UPDATE_ON_REBUILD),
                        new Group(Component.translatable("voxy.config.group.vanillaExtensions"),
                                new BoolOption(
                                        "voxy:distant_beacons",
                                        Component.translatable("voxy.config.compat.distantBeacons"),
                                        ()->CFG.distantBeacons, v->CFG.distantBeacons=v)
                                        .setImpact(OptionImpact.LOW),
                                new IntOption(
                                        "voxy:distant_beacon_distance",
                                        Component.translatable("voxy.config.compat.distantBeaconDistance"),
                                        ()->CFG.distantBeaconMaxChunks, v->CFG.distantBeaconMaxChunks=v,
                                        new Range(0, 512, 16))
                                        .setFormatter(VoxyConfigMenu::formatCreateDistance)
                                        .setImpact(OptionImpact.LOW)
                        )
                ).setEnablerAND("voxy:enabled", "voxy:rendering"),
                new Page(Component.translatable("voxy.config.experimental"),
                        new Group(Component.translatable("voxy.config.group.experimentalStill"),
                                new BoolOption(
                                        "voxy:experimental_cmd_list_hold",
                                        Component.translatable("voxy.config.experimental.cmdListHold"),
                                        ()->CFG.experimentalCmdListHold, v->CFG.experimentalCmdListHold=v)
                                        .setDefault(false)
                                        .setImpact(OptionImpact.MEDIUM),
                                new IntOption(
                                        "voxy:cmd_list_hold_max_frames",
                                        Component.translatable("voxy.config.experimental.cmdListHoldMaxFrames"),
                                        ()->CFG.cmdListHoldMaxFrames, v->CFG.cmdListHoldMaxFrames=v,
                                        new Range(2, 60, 1))
                                        .setDefault(4)
                                        .setEnablerInherit("voxy:experimental_cmd_list_hold")
                                        .setImpact(OptionImpact.LOW),
                                new BoolOption(
                                        "voxy:experimental_chunk_mask_reuse",
                                        Component.translatable("voxy.config.experimental.chunkMaskReuse"),
                                        ()->CFG.experimentalChunkMaskReuse, v->CFG.experimentalChunkMaskReuse=v)
                                        .setDefault(false)
                                        .setImpact(OptionImpact.LOW)
                        ),
                        new Group(Component.translatable("voxy.config.group.experimentalGpu"),
                                new BoolOption(
                                        "voxy:experimental_opaque_near_first",
                                        Component.translatable("voxy.config.experimental.opaqueNearFirst"),
                                        ()->CFG.experimentalOpaqueNearFirst, v->CFG.experimentalOpaqueNearFirst=v)
                                        .setDefault(false)
                                        .setImpact(OptionImpact.MEDIUM)
                                        .setPostChangeFlags(RENDER_RELOAD),
                                new BoolOption(
                                        "voxy:experimental_chunk_mask_half_res",
                                        Component.translatable("voxy.config.experimental.chunkMaskHalfRes"),
                                        ()->CFG.experimentalChunkMaskHalfRes, v->CFG.experimentalChunkMaskHalfRes=v)
                                        .setDefault(false)
                                        .setImpact(OptionImpact.MEDIUM)
                                        .setPostChangeFlags(RENDER_RELOAD),
                                new BoolOption(
                                        "voxy:experimental_hiz_compute",
                                        Component.translatable("voxy.config.experimental.hiZCompute"),
                                        ()->CFG.experimentalHiZCompute, v->CFG.experimentalHiZCompute=v)
                                        .setDefault(false)
                                        .setImpact(OptionImpact.LOW)
                                        .setPostChangeFlags(RENDER_RELOAD)
                        ),
                        new Group(Component.translatable("voxy.config.group.experimentalMemory"),
                                new IntOption(
                                        "voxy:section_array_pool_mib",
                                        Component.translatable("voxy.config.experimental.sectionArrayPoolMiB"),
                                        ()->CFG.sectionArrayPoolMiB, v->CFG.sectionArrayPoolMiB=v,
                                        new Range(25, 1024, 1))
                                        .setDefault(100)
                                        .setFormatter(v->Component.literal(v + " MiB"))
                                        //Heap, not frame time: a filled pool holds the whole budget
                                        .setImpact(OptionImpact.MEDIUM)
                        ),
                        new Group(Component.translatable("voxy.config.group.experimentalTransition"),
                                new BoolOption(
                                        "voxy:lod_boundary_fade",
                                        Component.translatable("voxy.config.general.lodBoundaryFade"),
                                        ()->CFG.enableLodBoundaryFade, v->CFG.enableLodBoundaryFade=v)
                                        .setImpact(OptionImpact.LOW),
                                new IntOption(
                                        "voxy:lod_boundary_fade_length",
                                        Component.translatable("voxy.config.general.lodBoundaryFadeLength"),
                                        ()->CFG.lodBoundaryFadeLength, v->CFG.lodBoundaryFadeLength=v,
                                        new Range(8, 64, 1))
                                        .setFormatter(v->Component.translatable("voxy.config.unit.blocks", v))
                                        .setEnablerInherit("voxy:lod_boundary_fade")
                                        .setImpact(OptionImpact.LOW),
                                new IntOption(
                                        "voxy:lod_boundary_inset",
                                        Component.translatable("voxy.config.general.lodBoundaryInset"),
                                        ()->CFG.lodBoundaryInset, v->CFG.lodBoundaryInset=v,
                                        new Range(8, 32, 1))
                                        .setFormatter(v->Component.translatable("voxy.config.unit.blocks", v))
                                        .setEnablerInherit("voxy:lod_boundary_fade")
                                        .setImpact(OptionImpact.LOW)
                        ),
                        new Group(Component.translatable("voxy.config.group.experimentalShaders"),
                                new BoolOption(
                                        "voxy:lod_lite_shading",
                                        Component.translatable("voxy.config.general.lodLiteShading"),
                                        ()->CFG.lodLiteShading, v->CFG.lodLiteShading=v)
                                        .setTooltipSupplier(v->liteShaderTooltip())
                                        .setImpact(OptionImpact.HIGH)
                                        .setEnablerInherit(s->IrisUtil.irisShaderPackEnabled(), ConfigState.UPDATE_ON_REBUILD)
                                        .setPostChangeFlags(RENDER_RELOAD, "voxy:iris_reload")
                        )
                ).setEnablerAND("voxy:enabled", "voxy:rendering"),
                new Page(Component.translatable("voxy.config.fakesight"),
                        new Group(Component.translatable("voxy.config.group.chunkRequests"),
                                new BoolOption(
                                        "voxy:fakesight_enabled",
                                        Component.translatable("voxy.config.fakesight.enabled"),
                                        ()->CFG.enableExtendedRequestDistance,
                                        v->CFG.enableExtendedRequestDistance=v)
                                        .setPostChangeFlags("voxy:refresh_chunk_request")
                                        .setImpact(OptionImpact.HIGH),
                                new IntOption(
                                        "voxy:fakesight_request_distance",
                                        Component.translatable("voxy.config.fakesight.distance"),
                                        CFG::getRequestDistance, v->CFG.requestDistance=v,
                                        new Range(VoxyConfig.MIN_REQUEST_DISTANCE,
                                                VoxyConfig.MAX_REQUEST_DISTANCE, 1))
                                        .setFormatter(v->Component.literal(Integer.toString(v)))
                                        .setPostChangeFlags("voxy:refresh_chunk_request")
                                        .setEnablerInherit("voxy:fakesight_enabled")
                                        .setImpact(OptionImpact.HIGH)
                        ).setEnablerInherit(s->Minecraft.getInstance().getConnection() == null
                                || Minecraft.getInstance().hasSingleplayerServer(), ConfigState.UPDATE_ON_REBUILD)
                ).setEnablerAND("voxy:enabled", "voxy:rendering"),
                new Page(Component.translatable("voxy.config.compat"),
                        new Group(Component.translatable("voxy.config.group.create"),
                                new BoolOption(
                                        "voxy:distant_trains",
                                        Component.translatable("voxy.config.compat.distantTrains"),
                                        ()->CFG.distantTrains, v->CFG.distantTrains=v)
                                        .setEnablerInherit(s->me.cortex.voxy.client.ServerCapabilities.canConfigureTrains(), ConfigState.UPDATE_ON_REBUILD)
                                        .setImpact(OptionImpact.LOW),
                                new IntOption(
                                        "voxy:distant_train_distance",
                                        Component.translatable("voxy.config.compat.distantTrainDistance"),
                                        ()->CFG.distantTrainMaxChunks, v->CFG.distantTrainMaxChunks=v,
                                        new Range(0, 192, 8))
                                        .setFormatter(VoxyConfigMenu::formatCreateDistance)
                                        .setEnablerInherit(s->me.cortex.voxy.client.ServerCapabilities.canConfigureTrains(), ConfigState.UPDATE_ON_REBUILD)
                                        .setImpact(OptionImpact.LOW),
                                new BoolOption(
                                        "voxy:distant_tracks",
                                        Component.translatable("voxy.config.compat.distantTracks"),
                                        ()->CFG.distantTracks, v->CFG.distantTracks=v)
                                        .setImpact(OptionImpact.LOW),
                                new IntOption(
                                        "voxy:distant_track_distance",
                                        Component.translatable("voxy.config.compat.distantTrackDistance"),
                                        ()->CFG.distantTrackMaxChunks, v->CFG.distantTrackMaxChunks=v,
                                        new Range(0, 192, 8))
                                        .setFormatter(VoxyConfigMenu::formatCreateDistance)
                                        .setImpact(OptionImpact.LOW),
                                new BoolOption(
                                        "voxy:distant_contraptions",
                                        Component.translatable("voxy.config.compat.distantContraptions"),
                                        ()->CFG.distantContraptions, v->CFG.distantContraptions=v)
                                        .setImpact(OptionImpact.LOW),
                                new IntOption(
                                        "voxy:distant_contraption_distance",
                                        Component.translatable("voxy.config.compat.distantContraptionDistance"),
                                        ()->CFG.distantContraptionMaxChunks, v->CFG.distantContraptionMaxChunks=v,
                                        new Range(0, 192, 8))
                                        .setFormatter(VoxyConfigMenu::formatCreateDistance)
                                        .setImpact(OptionImpact.LOW),
                                new BoolOption(
                                        "voxy:distant_kinetics",
                                        Component.translatable("voxy.config.compat.distantKinetics"),
                                        ()->CFG.distantKinetics, v->CFG.distantKinetics=v)
                                        .setImpact(OptionImpact.LOW),
                                new BoolOption(
                                        "voxy:kinetic_enclosed_culling",
                                        Component.translatable("voxy.config.compat.kineticEnclosedCulling"),
                                        ()->CFG.kineticEnclosedCulling, v->CFG.kineticEnclosedCulling=v)
                                        .setImpact(OptionImpact.LOW)
                        ).setEnablerInherit(s->createInstalled),
                        new Group(Component.translatable("voxy.config.group.aeronautics"),
                                new BoolOption(
                                        "voxy:sable_lod",
                                        Component.translatable("voxy.config.compat.sableLod"),
                                        ()->CFG.sableLodRendering, v->CFG.sableLodRendering=v)
                                        .setEnablerInherit(s->sableInstalled),
                                new IntOption(
                                        "voxy:sable_lod_distance",
                                        Component.translatable("voxy.config.compat.sableLodDistance"),
                                        ()->CFG.aeronauticsContraptionMaxChunks,
                                        v->CFG.aeronauticsContraptionMaxChunks=v,
                                        new Range(0, 192, 8))
                                        .setFormatter(VoxyConfigMenu::formatCreateDistance)
                                        .setImpact(OptionImpact.MEDIUM)
                                        .setEnablerInherit(s->sableInstalled),
                                new BoolOption(
                                        "voxy:distant_simulated_lasers",
                                        Component.translatable("voxy.config.compat.distantSimulatedLasers"),
                                        ()->CFG.distantSimulatedLasers, v->CFG.distantSimulatedLasers=v)
                                        .setImpact(OptionImpact.LOW)
                                        .setEnablerInherit(s->simulatedInstalled),
                                new IntOption(
                                        "voxy:distant_simulated_laser_distance",
                                        Component.translatable("voxy.config.compat.distantSimulatedLaserDistance"),
                                        ()->CFG.distantSimulatedLaserMaxChunks, v->CFG.distantSimulatedLaserMaxChunks=v,
                                        new Range(0, 192, 8))
                                        .setFormatter(VoxyConfigMenu::formatCreateDistance)
                                        .setImpact(OptionImpact.LOW)
                                        .setEnablerInherit(s->simulatedInstalled)
                        ).setEnablerInherit(s->sableInstalled || simulatedInstalled),
                        new Group(Component.translatable("voxy.config.group.powergrid"),
                                new BoolOption(
                                        "voxy:distant_powergrid_wires",
                                        Component.translatable("voxy.config.compat.distantPowerGridWires"),
                                        ()->CFG.distantPowerGridWires, v->CFG.distantPowerGridWires=v)
                                        .setImpact(OptionImpact.LOW),
                                new IntOption(
                                        "voxy:distant_powergrid_wire_distance",
                                        Component.translatable("voxy.config.compat.distantPowerGridWireDistance"),
                                        ()->CFG.distantPowerGridWireMaxChunks, v->CFG.distantPowerGridWireMaxChunks=v,
                                        new Range(0, 192, 8))
                                        .setFormatter(VoxyConfigMenu::formatCreateDistance)
                                        .setImpact(OptionImpact.LOW)
                        ).setEnablerInherit(s->powerGridInstalled),
                        new Group(Component.translatable("voxy.config.group.copycats"),
                                new BoolOption(
                                        "voxy:distant_copycats",
                                        Component.translatable("voxy.config.compat.distantCopycats"),
                                        ()->CFG.distantCopycats, v->CFG.distantCopycats=v)
                                        .setPostChangeFlags(RENDER_RELOAD)
                                        .setImpact(OptionImpact.MEDIUM),
                                new IntOption(
                                        "voxy:distant_copycats_distance",
                                        Component.translatable("voxy.config.compat.distantCopycatsDistance"),
                                        ()->CFG.distantCopycatsMaxChunks, v->CFG.distantCopycatsMaxChunks=v,
                                        new Range(0, 192, 8))
                                        .setFormatter(VoxyConfigMenu::formatCreateDistance)
                                        .setImpact(OptionImpact.MEDIUM)
                        ).setEnablerInherit(s->copycatsInstalled),
                        new Group(Component.translatable("voxy.config.group.framedblocks"),
                                new BoolOption(
                                        "voxy:distant_framedblocks",
                                        Component.translatable("voxy.config.compat.distantFramedBlocks"),
                                        ()->CFG.distantFramedBlocks, v->CFG.distantFramedBlocks=v)
                                        .setImpact(OptionImpact.LOW),
                                new IntOption(
                                        "voxy:distant_framedblocks_distance",
                                        Component.translatable("voxy.config.compat.distantFramedBlocksDistance"),
                                        ()->CFG.distantFramedBlocksMaxChunks, v->CFG.distantFramedBlocksMaxChunks=v,
                                        new Range(0, 192, 8))
                                        .setFormatter(VoxyConfigMenu::formatCreateDistance)
                                        .setImpact(OptionImpact.LOW)
                        ).setEnablerInherit(s->framedBlocksInstalled),
                        new Group(Component.translatable("voxy.config.group.littletiles"),
                                new BoolOption(
                                        "voxy:distant_littletiles",
                                        Component.translatable("voxy.config.compat.distantLittleTiles"),
                                        ()->CFG.distantLittleTiles, v->CFG.distantLittleTiles=v)
                                        .setImpact(OptionImpact.MEDIUM),
                                new IntOption(
                                        "voxy:distant_littletiles_distance",
                                        Component.translatable("voxy.config.compat.distantLittleTilesDistance"),
                                        ()->CFG.distantLittleTilesMaxChunks, v->CFG.distantLittleTilesMaxChunks=v,
                                        new Range(0, 192, 8))
                                        .setFormatter(VoxyConfigMenu::formatCreateDistance)
                                        .setImpact(OptionImpact.MEDIUM)
                        ).setEnablerInherit(s->littleTilesInstalled),
                        new Group(Component.translatable("voxy.config.group.domum"),
                                new BoolOption(
                                        "voxy:distant_domum",
                                        Component.translatable("voxy.config.compat.distantDomum"),
                                        ()->CFG.distantDomum, v->CFG.distantDomum=v)
                                        .setImpact(OptionImpact.MEDIUM),
                                new IntOption(
                                        "voxy:distant_domum_distance",
                                        Component.translatable("voxy.config.compat.distantDomumDistance"),
                                        ()->CFG.distantDomumMaxChunks, v->CFG.distantDomumMaxChunks=v,
                                        new Range(0, 192, 8))
                                        .setFormatter(VoxyConfigMenu::formatCreateDistance)
                                        .setImpact(OptionImpact.MEDIUM)
                        ).setEnablerInherit(s->domumInstalled),
                        new Group(Component.translatable("voxy.config.group.seasons"),
                                new BoolOption(
                                        "voxy:es_snow_lod",
                                        Component.translatable("voxy.config.compat.esSnowLod"),
                                        ()->CFG.eclipticSeasonsSnowLod, v->CFG.eclipticSeasonsSnowLod=v),
                                new BoolOption(
                                        "voxy:es_lod_auto_reload",
                                        Component.translatable("voxy.config.compat.esLodAutoReload"),
                                        ()->CFG.eclipticSeasonsLodAutoReload, v->CFG.eclipticSeasonsLodAutoReload=v),
                                new BoolOption(
                                        "voxy:es_reload_on_season_change",
                                        Component.translatable("voxy.config.compat.esReloadOnSeasonChange"),
                                        ()->CFG.eclipticSeasonsReloadOnSeasonChange, v->CFG.eclipticSeasonsReloadOnSeasonChange=v)
                        ).setEnablerInherit(s->seasonsInstalled)
                ).setEnabler("voxy:enabled"));

    }


    private static Component formatCreateDistance(int chunks) {
        return chunks == 0
                ? Component.translatable("voxy.config.compat.distanceFollowLod")
                : Component.translatable("voxy.config.compat.distanceChunks", chunks);
    }

    private static Component liteShaderTooltip() {
        var state = LiteShaderStatus.get();
        Component status = switch (state.code()) {
            case ACTIVE -> Component.translatable("voxy.config.general.lodLiteShading.status.active",
                    state.packName(), state.testedVersions());
            case REQUESTED -> Component.translatable("voxy.config.general.lodLiteShading.status.requested");
            case NO_VOXY_PATCH -> Component.translatable("voxy.config.general.lodLiteShading.status.noPatch");
            case MISSING_PROGRAMS -> Component.translatable("voxy.config.general.lodLiteShading.status.missingPrograms");
            case MISSING_CONTRACT -> Component.translatable("voxy.config.general.lodLiteShading.status.missingContract");
            case CONTRACT_MISMATCH -> Component.translatable("voxy.config.general.lodLiteShading.status.contractMismatch");
            case API_MISMATCH -> Component.translatable("voxy.config.general.lodLiteShading.status.apiMismatch",
                    state.detail());
            case TRANSITION_REQUIRED -> Component.translatable("voxy.config.general.lodLiteShading.status.transitionRequired");
            case ERROR -> Component.translatable("voxy.config.general.lodLiteShading.status.error", state.detail());
            case DISABLED -> Component.translatable("voxy.config.general.lodLiteShading.status.disabled");
        };
        return Component.translatable("voxy.config.general.lodLiteShading.tooltip")
                .append("\n")
                .append(status);
    }

}

package me.cortex.voxy.client.config;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.cortex.voxy.client.core.SSAO;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import me.cortex.voxy.commonImpl.VoxyCommon;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
//? if 1.20.1
import me.jellysquid.mods.sodium.client.gui.options.storage.OptionStorage;

public class VoxyConfig
//? if 1.20.1
    implements OptionStorage<VoxyConfig>
{
    public enum LeafLodMode { FAST, BALANCED, QUALITY }
    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.TRANSIENT)
            .create();

    public static VoxyConfig CONFIG = loadOrCreate();

    public boolean enabled = true;
    public boolean enableRendering = true;
    public boolean ingestEnabled = true;
    public int lodDistance = 64;
    public float sectionRenderDistance = 16;
    public int serviceThreads = (int) Math.max(CpuLayout.getCoreCount()/1.5, 1);
    public float subDivisionSize = 64;
    public int skyFogDistance = 96;
    public float fogIntensity = 1.0f;
    public float fogDensity = 0.0f;
    public boolean adaptCloudDistance = true;
    public int cloudDistance = 0;
    public boolean dontUseSodiumBuilderThreads = false;
    public int renderPressure = 2;
    public String leafLodMode = "balanced";
    public int earthCurveRatio = 0;
    public int fogDistancePercent = 100;
    public int biomeBlendRadius = 2;
    public String biomeBlendScope = "water";
    public boolean enableExtendedRequestDistance = false;
    public int requestDistance = 48;
    public boolean showJoinMessage = true;
    public boolean upgradeCleanupNoticeShown = false;

    public String ssaoMode;

    public boolean useEnvironmentalFog = true;

    public int getRenderPressureLevel() {
        if (this.renderPressure < 0 || this.renderPressure > 4) this.renderPressure = 2;
        return this.renderPressure;
    }

    public LeafLodMode getLeafLodMode() {
        if (this.leafLodMode == null) return LeafLodMode.BALANCED;
        try {
            return LeafLodMode.valueOf(this.leafLodMode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return LeafLodMode.BALANCED;
        }
    }

    public void setLeafLodMode(LeafLodMode mode) {
        this.leafLodMode = mode.name().toLowerCase(Locale.ROOT);
    }

    public SSAO.SSAOMode getSSAOMode() {
        if (this.ssaoMode == null) return SSAO.SSAOMode.AUTO;
        try {
            return SSAO.SSAOMode.valueOf(this.ssaoMode.toUpperCase(Locale.ROOT));
        } catch (Exception e) { return SSAO.SSAOMode.AUTO; }
    }

    public void setSSAOMode(SSAO.SSAOMode mode) {
        this.ssaoMode = mode.name().toLowerCase(Locale.ROOT);
    }

    private static VoxyConfig loadOrCreate() {
        if (VoxyCommon.isAvailable()) {
            var path = getConfigPath();
            if (Files.exists(path)) {
                try (FileReader reader = new FileReader(path.toFile())) {
                    var conf = GSON.fromJson(reader, VoxyConfig.class);
                    if (conf != null) {
                        conf.sanitize();
                        return conf;
                    } else {
                        Logger.error("Failed to load voxy config, resetting");
                    }
                } catch (IOException e) {
                    Logger.error("Could not parse config", e);
                }
            }
            Logger.info("Config doesnt exist, creating new");
            var config = new VoxyConfig();
            config.save();
            return config;
        } else {
            var config = new VoxyConfig();
            config.enabled = false;
            config.enableRendering = false;
            return config;
        }
    }

    public void save() {
        if (!VoxyCommon.isAvailable()) {
            Logger.info("Not saving config since voxy is unavalible");
            return;
        }

        this.sanitize();
        Path path = getConfigPath();
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(temporary, GSON.toJson(this));
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Logger.error("Failed to write config file", e);
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
        }
    }

    public void sanitize() {
        this.sectionRenderDistance = clamp(this.sectionRenderDistance, 2.0f, 64.0f);
        this.subDivisionSize = clamp(this.subDivisionSize, 28.0f, 256.0f);
        this.skyFogDistance = clamp(this.skyFogDistance, 0, 1024);
        this.fogIntensity = clamp(this.fogIntensity, 0.0f, 1.0f);
        this.fogDensity = clamp(this.fogDensity, 0.0f, 1.0f);
        this.fogDistancePercent = clamp(this.fogDistancePercent, 5, 200);
        this.biomeBlendRadius = clamp(this.biomeBlendRadius, 0, 7);
        this.requestDistance = clamp(this.requestDistance, 8, 48);
        this.lodDistance = clamp(this.lodDistance, 2, 64);
        if (!"water".equals(this.biomeBlendScope) && !"water_grass".equals(this.biomeBlendScope)) {
            this.biomeBlendScope = "water";
        }
        this.setLeafLodMode(this.getLeafLodMode());
    }

    public int getRequestDistance() { return clamp(this.requestDistance, 8, 48); }

    public int getLodRenderDistanceBlocks() {
        return clamp(Math.round(this.sectionRenderDistance * 32.0f * 16.0f), 64, 32768);
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }

    private static Path getConfigPath() {
        return VoxyCommon.getPlatformUtil().getConfigDir().resolve("voxy-config.json");
    }

    //? if 1.20.1 {
    @Override
    public VoxyConfig getData() {
        return this;
    }
    //? }

    public boolean isRenderingEnabled() {
        return VoxyCommon.isAvailable() && this.enabled && this.enableRendering;
    }
}

package me.apika.nephritis;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import me.apika.nephritis.command.NephritisCommand;
import me.apika.nephritis.entity.ColliderSkip;
import me.apika.nephritis.entity.EntityQueryIndex;
import me.apika.nephritis.entity.CrammingDispatcher;
import me.apika.nephritis.pregen.PregenManager;
import me.apika.nephritis.redstone.RedstoneOptimizer;
import me.apika.nephritis.hopper.HopperHighway;
import me.apika.nephritis.monitor.MonitorManager;
import me.apika.nephritis.chunkforce.PredictiveChunkForce;

@Mod(Nephritis.MOD_ID)
public final class Nephritis {
    public static final String MOD_ID = "nephritis";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Nephritis() {
        MinecraftForge.EVENT_BUS.addListener(Nephritis::registerCommands);
        MinecraftForge.EVENT_BUS.register(EntityQueryIndex.class);
        MinecraftForge.EVENT_BUS.register(CrammingDispatcher.class);
        MinecraftForge.EVENT_BUS.register(PregenManager.class);
        MinecraftForge.EVENT_BUS.register(MonitorManager.class);
        MinecraftForge.EVENT_BUS.register(PredictiveChunkForce.class);
        LOGGER.info("Nephritis Forge port {} loaded for Minecraft 1.20.1", BuildInfo.VERSION);
        MonitorManager.logHardware();
        me.apika.nephritis.nativebridge.NalimBridge.initialize();
        LOGGER.info("Cramming optimizer: {} backend={}", CrammingDispatcher.isEnabled() ? "enabled" : "disabled", CrammingDispatcher.backendStatus());
        LOGGER.info("Hopper highway: {}", HopperHighway.isEnabled() ? "enabled" : "disabled");
        LOGGER.info("Redstone optimizer: {}", RedstoneOptimizer.status());
    }

    public static String version() {
        return BuildInfo.VERSION;
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        NephritisCommand.register(event.getDispatcher());
    }

    private static final class BuildInfo {
        private static final String VERSION = load();

        private static String load() {
            try (InputStream in = Nephritis.class.getResourceAsStream("/nephritis-build.properties")) {
                if (in == null) return "unknown";
                Properties properties = new Properties();
                properties.load(in);
                return properties.getProperty("version", "unknown");
            } catch (IOException e) {
                return "unknown";
            }
        }
    }
}

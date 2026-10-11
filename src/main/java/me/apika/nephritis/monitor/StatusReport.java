package me.apika.nephritis.monitor;

import me.apika.nephritis.Nephritis;
import me.apika.nephritis.entity.CrammingDispatcher;
import me.apika.nephritis.nativebridge.NalimBridge;
import me.apika.nephritis.redstone.RedstoneOptimizer;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class StatusReport {
    private static final Pattern MIXIN_ARRAY = Pattern.compile("\"mixins\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL);
    private static final Pattern STRING = Pattern.compile("\"([^\"]+)\"");

    private StatusReport() {}

    public static List<String> lines() {
        List<String> lines = new ArrayList<>();
        lines.add("Nephritis version: " + Nephritis.version());
        lines.add("Nalim: " + NalimBridge.peekStatus() + ", " + jvmFlags());
        lines.add("Native library: " + NalimBridge.nativeDescription());
        lines.add("Host: " + System.getProperty("os.name", "unknown") + " " + System.getProperty("os.arch", "unknown"));
        lines.add("Cramming: " + (CrammingDispatcher.isEnabled() ? "ON" : "OFF")
            + ", backend=" + CrammingDispatcher.backendStatus());
        lines.add("Redstone: " + RedstoneOptimizer.status());
        List<String> mixins = registeredMixins();
        lines.add("Mixins registered in nephritis.mixins.json (" + mixins.size() + "): " + String.join(", ", mixins));
        return lines;
    }

    private static String jvmFlags() {
        try {
            List<String> arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
            boolean jvmci = arguments.contains("-XX:+EnableJVMCI");
            boolean agent = arguments.stream().anyMatch(a -> a.startsWith("-javaagent:") && a.contains("nalim"));
            boolean module = ModuleLayer.boot().findModule("jdk.internal.vm.ci").isPresent();
            return "EnableJVMCI=" + jvmci + ", nalimAgent=" + agent + ", jvmciModule=" + module;
        } catch (RuntimeException | LinkageError e) {
            return "jvm flags unavailable";
        }
    }

    private static List<String> registeredMixins() {
        List<String> names = new ArrayList<>();
        try (InputStream in = Nephritis.class.getResourceAsStream("/nephritis.mixins.json")) {
            if (in == null) return names;
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher array = MIXIN_ARRAY.matcher(json);
            if (!array.find()) return names;
            Matcher entry = STRING.matcher(array.group(1));
            while (entry.find()) names.add(entry.group(1));
        } catch (IOException ignored) {
            return names;
        }
        return names;
    }
}

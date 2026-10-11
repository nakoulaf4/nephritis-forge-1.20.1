package me.apika.nephritis.nativebridge;

import me.apika.nephritis.Nephritis;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.jar.JarFile;


public final class NalimBridge {
    private static volatile boolean initialized;
    private static volatile boolean available;
    private static volatile boolean attempted;
    private static volatile String nativeResource = "none";
    private static volatile String nativeSha256 = "n/a";

    private NalimBridge() {}

    public static boolean isAvailable() {
        ensureInitialized();
        return available;
    }


    public static void initialize() {
        ensureInitialized();
    }


    public static String nativeDescription() {
        return nativeResource + " sha256=" + nativeSha256;
    }

    public static String peekStatus() {
        if (available) return "available";
        if (attempted) return "unavailable";
        try {
            Class.forName("one.nalim.Linker", false, ClassLoader.getSystemClassLoader());
            return "configured";
        } catch (ClassNotFoundException ignored) {
            return "not-installed";
        }
    }

    public static boolean verifyRuntimeJar(Path jar) {
        if (jar == null || !Files.isRegularFile(jar)) return false;
        try (JarFile file = new JarFile(jar.toFile())) {
            return file.getEntry("one/nalim/Linker.class") != null
                && file.getEntry("one/nalim/Agent.class") != null
                && file.getEntry("one/nalim/Link.class") != null;
        } catch (IOException ignored) {
            return false;
        }
    }

    public static void compute(double[] request, double[] result, int count) {
        ensureInitialized();
        if (!available) {
            throw new IllegalStateException("Nephritis Nalim backend is unavailable");
        }
        RustCrammingNative.compute(request, result, count);
    }

    public static void computeRedstoneBfs(double[] request, double[] result, int count) {
        ensureInitialized();
        if (!available) {
            throw new IllegalStateException("Nephritis Nalim backend is unavailable");
        }
        RustRedstoneBfsNative.compute(request, result, count);
    }

    public static void disable(String reason, Throwable error) {
        if (available || !attempted) {
            if (error == null) {
                Nephritis.LOGGER.warn("Nalim backend disabled: {}", reason);
            } else {
                Nephritis.LOGGER.warn("Nalim backend disabled: {}", reason, error);
            }
        }
        available = false;
    }

    private static void ensureInitialized() {
        if (initialized) return;
        synchronized (NalimBridge.class) {
            if (initialized) return;
            attempted = true;
            try {
                Path nativePath = prepareNativeLibrary();
                Class<?> linker = Class.forName("one.nalim.Linker", false,
                    ClassLoader.getSystemClassLoader());
                java.lang.reflect.Method loadLibrary = linker.getMethod("loadLibrary", String.class);
                loadLibrary.invoke(null, nativePath.toAbsolutePath().toString());
                java.lang.reflect.Method linkClass = linker.getMethod("linkClass", Class.class);
                linkClass.invoke(null, RustCrammingNative.class);
                linkClass.invoke(null, RustRedstoneBfsNative.class);

                // Verify both native entry points immediately. This is deliberately tiny
                // and side-effect free: the Rust functions only operate on primitive arrays.
                smokeTest();
                available = true;
                Nephritis.LOGGER.info("Nephritis native backend: Nalim + Rust ACTIVE");
            } catch (CorruptNativeException e) {
                available = false;
                Nephritis.LOGGER.warn(
                    "Nephritis native library {} is corrupted: {}. Rebuild it with gradlew buildRustNative. Using Java fallback.",
                    e.resource, e.getMessage());
            } catch (Throwable t) {
                available = false;
                Nephritis.LOGGER.warn(
                    "Nephritis native backend unavailable; using Java fallback. " +
                    "Install Nalim and build nephritis_native to enable it.", t);
            } finally {
                initialized = true;
            }
        }
    }


    private static void smokeTest() {
        double[] crammingRequest = {
            1.0D, 1.0D, 0.0D, 0.0D, 0.5D, 0.0D, 1.0D, 1.0D
        };
        double[] crammingResult = new double[4];
        RustCrammingNative.compute(crammingRequest, crammingResult, 1);
        if (crammingResult[0] != 1.0D
            || crammingResult[1] != 0.0D
            || crammingResult[2] != 0.0D
            || crammingResult[3] != 0.0D) {
            throw new IllegalStateException("Nalim/Rust cramming smoke-test returned an invalid result");
        }

        double[] redstoneRequest = {
            15.0D, -1.0D, -1.0D, -1.0D, -1.0D, -1.0D, -1.0D, -1.0D, -1.0D
        };
        double[] redstoneResult = new double[1];
        RustRedstoneBfsNative.compute(redstoneRequest, redstoneResult, 1);
        if (redstoneResult[0] != 15.0D) {
            throw new IllegalStateException("Nalim/Rust redstone BFS smoke-test returned an invalid result");
        }
    }

    private static Path prepareNativeLibrary() throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String resource;
        String mappedName;
        boolean windows = os.contains("win");
        if (windows) {
            if (!(arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64"))) {
                throw new IOException("Unsupported Windows architecture for Nephritis native backend: " + arch);
            }
            resource = "/assets/nephritis/natives/windows/nephritis_native.dll";
            mappedName = "nephritis_native.dll";
        } else if (os.contains("linux")) {
            if (arch.contains("aarch64") || arch.contains("arm64")) {
                resource = "/assets/nephritis/natives/linux-aarch64/libnephritis_native.so";
            } else if (arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64")) {
                resource = "/assets/nephritis/natives/linux/libnephritis_native.so";
            } else {
                throw new IOException("Unsupported Linux architecture for Nephritis native backend: " + arch);
            }
            mappedName = "libnephritis_native.so";
        } else {
            throw new IOException("Unsupported OS for Nephritis native backend: " + os);
        }

        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "nephritis-native", "1.20.1");
        Files.createDirectories(dir);
        Path target = dir.resolve(mappedName);
        Path hashFile = dir.resolve(mappedName + ".sha256");

        try (InputStream in = RustCrammingNative.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Native resource not found: " + resource);
            }
            byte[] bytes = in.readAllBytes();
            String hash = sha256(bytes);
            nativeResource = resource;
            nativeSha256 = hash;
            String problem = headerProblem(windows, bytes);
            if (problem != null) {
                throw new CorruptNativeException(resource, problem);
            }
            String oldHash = Files.exists(hashFile) ? Files.readString(hashFile).trim() : "";
            if (!hash.equalsIgnoreCase(oldHash) || !Files.exists(target) || Files.size(target) == 0L) {
                Files.write(target, bytes);
                Files.writeString(hashFile, hash);
            }
        }

        // Nalim must load the library itself so its Linker can resolve the
        // @Link targets into JVMCI call sites. Calling System.load directly
        // and then Linker.linkClass would make Nalim attempt a second load by
        // library name.
        return target;
    }

    private static String headerProblem(boolean windows, byte[] bytes) {
        if (windows) {
            if (bytes.length < 0x40 || bytes[0] != 'M' || bytes[1] != 'Z') {
                return "missing MZ header";
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int peOffset = buffer.getInt(0x3C);
            if (peOffset < 0x40 || peOffset > bytes.length - 6) {
                return "invalid e_lfanew " + peOffset;
            }
            if (bytes[peOffset] != 'P' || bytes[peOffset + 1] != 'E'
                || bytes[peOffset + 2] != 0 || bytes[peOffset + 3] != 0) {
                return "missing PE signature at offset " + peOffset;
            }
            int machine = buffer.getShort(peOffset + 4) & 0xFFFF;
            if (machine != 0x8664) {
                return "machine is 0x" + Integer.toHexString(machine) + ", expected 0x8664";
            }
            return null;
        }
        if (bytes.length < 20 || bytes[0] != 0x7F || bytes[1] != 'E' || bytes[2] != 'L' || bytes[3] != 'F') {
            return "missing ELF header";
        }
        return null;
    }

    private static final class CorruptNativeException extends IOException {
        private final String resource;

        private CorruptNativeException(String resource, String problem) {
            super(problem);
            this.resource = resource;
        }
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte value : hash) result.append(String.format("%02x", value));
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable in this JVM", e);
        }
    }
}

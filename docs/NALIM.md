# Nalim integration — Forge 1.20.1

This port uses the real Nalim runtime as an external JVMCI component. Nalim is not bundled into the Nephritis jar because it is GPL-2.0 licensed.

## Required runtime

The real upstream Nalim v1.1 jar is already included at:

    libs/nalim.jar

The included jar was verified to contain:

    one/nalim/Agent.class
    one/nalim/Linker.class
    one/nalim/Link.class

The Gradle task `verifyNalim` validates these entries.

## Development launch

When `libs/nalim.jar` exists, the ForgeGradle client/server run configurations add:

    -XX:+UnlockExperimentalVMOptions
    -XX:+EnableJVMCI
    --add-modules=jdk.internal.vm.ci
    -Xbootclasspath/a:/absolute/path/to/libs/nalim.jar
    -javaagent:/absolute/path/to/libs/nalim.jar
    --add-exports=jdk.internal.vm.ci/jdk.vm.ci.code=ALL-UNNAMED
    --add-exports=jdk.internal.vm.ci/jdk.vm.ci.code.site=ALL-UNNAMED
    --add-exports=jdk.internal.vm.ci/jdk.vm.ci.hotspot=ALL-UNNAMED
    --add-exports=jdk.internal.vm.ci/jdk.vm.ci.meta=ALL-UNNAMED
    --add-exports=jdk.internal.vm.ci/jdk.vm.ci.runtime=ALL-UNNAMED

Nalim is kept on the boot class path so Forge's module classloader does not turn it into an isolated automatic module. Nalim is then in the unnamed module and the `ALL-UNNAMED` exports apply. Startup also performs a side-effect-free native smoke-test of both Rust entry points before reporting the backend as ACTIVE.

## Native loading

Nephritis extracts its host native library to the JVM temp directory, then calls Nalim's `Linker.loadLibrary(absolutePath)` followed by `Linker.linkClass(...)` for the native declaration classes.

It intentionally does not call `System.load(...)` first. This keeps the native loading/linking operation inside Nalim's linker instead of mixing two loading paths.

## ABI contract

The Java declarations are:

    public static native void compute(double[] request, double[] result, int count);

The Rust exports are `extern "C"` functions. Therefore the Nalim declarations use the default `@Link(..., naked = false)` behavior. Nalim then performs the Java-to-native ABI adaptation, including passing primitive-array data pointers to native code.

Only primitive arrays and integers cross the native boundary. No Minecraft object, `Level`, `Entity`, `BlockState`, or Forge capability reference is passed to Rust.

## Failure behavior

If Nalim is absent, the native library is missing, linking fails, or a native call throws, Nephritis disables the native backend and keeps the Java implementation active.

## Production

For a production Forge server/client, the JVM that launches Forge must have the real Nalim jar available and must be started with the JVMCI + `-javaagent` arguments above. The source distribution includes the Nalim runtime in `libs/`; the built Nephritis mod jar intentionally does not embed Nalim.

SHA-256 of included Nalim v1.1: `fd0b1e9f06a82a367f995bbccd0b54934a198a7b4b2305d11277549a3fe56218`.

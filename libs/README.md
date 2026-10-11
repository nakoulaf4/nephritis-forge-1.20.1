# Nalim runtime

This project includes the real upstream Nalim `v1.1` runtime at `libs/nalim.jar`.

Upstream: https://github.com/apangin/nalim
Release: https://github.com/apangin/nalim/releases/tag/v1.1
SHA-256: fd0b1e9f06a82a367f995bbccd0b54934a198a7b4b2305d11277549a3fe56218
License: GPL-2.0; see `LICENSE-NALIM-GPL-2.0.txt`.

The ForgeGradle `runClient` and `runServer` configurations automatically add:

    -XX:+UnlockExperimentalVMOptions
    -XX:+EnableJVMCI
    -javaagent:/absolute/path/to/libs/nalim.jar

The native Nephritis kernels are still built separately with `gradlew buildWithNative` because this environment cannot provide a Windows Rust toolchain. Once the DLL is compiled and copied into the Nephritis resources, `NalimBridge` loads it with Nalim's `Linker`.

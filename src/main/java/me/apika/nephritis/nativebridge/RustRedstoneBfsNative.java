package me.apika.nephritis.nativebridge;

import one.nalim.Link;


public final class RustRedstoneBfsNative {
    private RustRedstoneBfsNative() {}

    @Link(name = "nephritis_compute_redstone_bfs")
    public static native void compute(double[] request, double[] result, int count);
}

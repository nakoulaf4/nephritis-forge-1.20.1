package me.apika.nephritis.nativebridge;

import one.nalim.Link;


public final class RustCrammingNative {
    private RustCrammingNative() {}

    @Link(name = "nephritis_compute_cramming")
    public static native void compute(double[] request, double[] result, int count);
}

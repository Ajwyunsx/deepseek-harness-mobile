package com.dshmobile.app;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class NodeRuntimeCheck {
    public static void main(String[] args) throws Exception {
        File scratch = new File(args[0]);
        check(!NodeRuntime.isSupported(new File(scratch, "missing")), "missing runtime");
        check(!supported(scratch, "22.20.0"), "Node 22");
        check(!supported(scratch, "24.8.0"), "Node 24.8");
        check(supported(scratch, "24.9.0"), "Node 24.9");
        check(supported(scratch, "25.0.0"), "newer major");
        System.out.println("NODE_RUNTIME_CHECK=true");
    }

    private static boolean supported(File scratch, String version) throws Exception {
        String[] parts = version.split("\\.");
        File rootfs = new File(scratch, "node-" + version);
        File bin = new File(rootfs, "opt/node/bin/node");
        File header = new File(rootfs, "opt/node/include/node/node_version.h");
        if (!bin.getParentFile().mkdirs() && !bin.getParentFile().isDirectory()) throw new AssertionError(bin.toString());
        if (!header.getParentFile().mkdirs() && !header.getParentFile().isDirectory()) throw new AssertionError(header.toString());
        Files.write(bin.toPath(), new byte[]{0});
        String declarations = "#define NODE_MAJOR_VERSION " + parts[0] + "\n"
                + "#define NODE_MINOR_VERSION " + parts[1] + "\n"
                + "#define NODE_PATCH_VERSION " + parts[2] + "\n";
        Files.write(header.toPath(), declarations.getBytes(StandardCharsets.UTF_8));
        return NodeRuntime.isSupported(rootfs);
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError("Unexpected Node runtime support result: " + label);
    }
}

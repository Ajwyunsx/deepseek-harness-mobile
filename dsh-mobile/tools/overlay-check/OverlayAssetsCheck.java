package com.dshmobile.app;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

public final class OverlayAssetsCheck {
    public static void main(String[] args) throws Exception {
        File source = new File(args[0]);
        File destination = new File(args[1]);
        Map<String, byte[]> assets = new HashMap<>();
        try (Stream<java.nio.file.Path> paths = Files.walk(source.toPath())) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                String name = "harness-overlay/" + source.toPath().relativize(path).toString().replace(File.separatorChar, '/');
                try {
                    assets.put(name, Files.readAllBytes(path));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
        Context context = new Context() {
            private final AssetManager manager = new AssetManager(assets);

            @Override
            public AssetManager getAssets() {
                return manager;
            }
        };

        HarnessOverlayAssets.extract(context, destination);
        HarnessOverlayAssets.extract(context, destination);
        assertSame(source, destination, "manifest.tsv");
        assertSame(source, destination, "harness-version.txt");
        assertSame(source, destination, "deepseek-ai-dsh-fs-local/lib/index.js");
        assertSame(source, destination, "deepseek-ai-dsh-fs-local/lib/types/fsio.d.ts");
        System.out.println("OVERLAY_ASSETS_EXTRACT=true");
    }

    private static void assertSame(File source, File destination, String path) throws IOException {
        File expected = new File(source, path.replace('/', File.separatorChar));
        File actual = new File(destination, path.replace('/', File.separatorChar));
        if (!actual.isFile() || !Arrays.equals(Files.readAllBytes(expected.toPath()), Files.readAllBytes(actual.toPath()))) {
            throw new AssertionError("Extracted asset differs: " + path);
        }
    }
}

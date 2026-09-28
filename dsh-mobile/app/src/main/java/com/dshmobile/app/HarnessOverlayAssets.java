package com.dshmobile.app;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;

/**
 * 把 APK assets 里的 harness 覆盖层展开成真实文件。
 *
 * <p>assets 只能通过 {@link android.content.res.AssetManager} 逐个读取，而
 * {@link HarnessOverlay} 需要普通文件（还要算 sha256），所以先整体展开到应用缓存
 * 目录，再交给覆盖逻辑。展开是幂等的：同名文件直接覆盖。
 */
final class HarnessOverlayAssets {

    /** assets 下的覆盖层根目录，与 dsh-mobile/tools/build-harness-overlay.mjs 一致。 */
    private static final String ASSET_ROOT = "harness-overlay";

    private HarnessOverlayAssets() {
    }

    /**
     * 展开覆盖层，返回展开后的目录。
     *
     * @param context     用于访问 assets 的上下文
     * @param destination 展开目标目录
     * @return 展开后的目录；APK 里没有该资产目录时同样返回该目录（内容可能为空）
     * @throws IOException 目标目录无法创建时
     */
    static File extract(Context context, File destination) throws IOException {
        if (!destination.isDirectory() && !destination.mkdirs()) {
            throw new IOException("无法创建覆盖层目录: " + destination);
        }
        copyTree(context, ASSET_ROOT, destination);
        return destination;
    }

    /** 递归展开一个资产条目；返回是否展开了任何内容。 */
    private static boolean copyTree(Context context, String assetPath, File target) throws IOException {
        String[] children;
        try {
            children = context.getAssets().list(assetPath);
        } catch (IOException e) {
            return false;
        }
        if (children == null || children.length == 0) {
            byte[] content = read(context, assetPath);
            if (content == null) return false;
            try (OutputStream out = Files.newOutputStream(target.toPath())) {
                out.write(content);
            }
            return true;
        }
        boolean copied = false;
        for (String child : children) {
            File childTarget = new File(target, child);
            if (copyTree(context, assetPath + "/" + child, childTarget)) copied = true;
        }
        return copied;
    }

    /** 读取一个资产文件；不存在时返回 null。 */
    private static byte[] read(Context context, String assetPath) {
        try (InputStream in = context.getAssets().open(assetPath)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) > 0) out.write(buffer, 0, count);
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }
}

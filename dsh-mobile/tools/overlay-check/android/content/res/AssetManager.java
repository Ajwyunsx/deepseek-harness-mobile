package android.content.res;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class AssetManager {
    private final Map<String, byte[]> assets;

    public AssetManager(Map<String, byte[]> assets) {
        this.assets = assets;
    }

    public InputStream open(String path) throws IOException {
        byte[] content = assets.get(path);
        if (content == null) throw new IOException("Asset not found: " + path);
        return new ByteArrayInputStream(content);
    }

    public String[] list(String path) throws IOException {
        String prefix = path + "/";
        Set<String> children = new TreeSet<>();
        for (String asset : assets.keySet()) {
            if (!asset.startsWith(prefix)) continue;
            String remainder = asset.substring(prefix.length());
            int slash = remainder.indexOf('/');
            children.add(slash < 0 ? remainder : remainder.substring(0, slash));
        }
        return children.toArray(new String[0]);
    }
}

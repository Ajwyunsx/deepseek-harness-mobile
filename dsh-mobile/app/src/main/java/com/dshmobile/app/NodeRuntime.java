package com.dshmobile.app;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 当前 harness 所需的 Node.js 主版本检查。 */
final class NodeRuntime {
    static final int REQUIRED_MAJOR = 24;
    private static final int REQUIRED_MINOR = 9;
    private static final Pattern MAJOR_FIELD = Pattern.compile("#define\\s+NODE_MAJOR_VERSION\\s+(\\d+)");
    private static final Pattern MINOR_FIELD = Pattern.compile("#define\\s+NODE_MINOR_VERSION\\s+(\\d+)");

    private NodeRuntime() {
    }

    /** 官方 Node 包同时提供可执行文件与声明其主版本的头文件。 */
    static boolean isSupported(File rootfs) {
        if (!new File(rootfs, "opt/node/bin/node").isFile()) return false;
        File header = new File(rootfs, "opt/node/include/node/node_version.h");
        if (!header.isFile()) return false;
        try {
            String declarations = new String(Files.readAllBytes(header.toPath()), StandardCharsets.UTF_8);
            Matcher major = MAJOR_FIELD.matcher(declarations);
            Matcher minor = MINOR_FIELD.matcher(declarations);
            if (!major.find() || !minor.find()) return false;
            int majorVersion = Integer.parseInt(major.group(1));
            int minorVersion = Integer.parseInt(minor.group(1));
            return majorVersion > REQUIRED_MAJOR
                    || majorVersion == REQUIRED_MAJOR && minorVersion >= REQUIRED_MINOR;
        } catch (IOException | NumberFormatException e) {
            return false;
        }
    }
}

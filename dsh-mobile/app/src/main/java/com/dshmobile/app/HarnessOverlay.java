package com.dshmobile.app;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用本仓库构建的 harness 包覆盖容器内 npm 装好的同名包。
 *
 * <p>容器里的 {@code @deepseek-ai/dsh} 是从 npm 装的上游发行版，本仓库对 harness
 * 源码的修复（例如 {@code dsh-fs-local} 的 createIfAbsent 无硬链接降级）不会随
 * APK 进入容器。这里把 {@code assets/harness-overlay/} 里随 APK 携带的、由本仓库
 * 构建的同名同版本包覆盖到已安装目录，使这些修复在设备上生效。
 *
 * <p>覆盖<b>按版本匹配</b>：安装到的包版本必须与清单记录的版本一致，否则整个包
 * 跳过并在摘要里说明原因（宁可保留上游行为，也不装出一个版本错配的树）。每个文件
 * 写入后重新计算 sha256 与清单核对，因此"覆盖成功"可以被证伪，而不是凭写入成功
 * 推断。
 *
 * <p>本类只用 {@code java.io}/{@code java.security}，不触碰 Android API，因此可以
 * 脱离设备在主机上直接编译运行，对真实的 npm 安装树做验证。
 */
public final class HarnessOverlay {

    /**
     * 覆盖层清单文件名，由 dsh-mobile/tools/build-harness-overlay.mjs 生成。
     */
    private static final String MANIFEST_NAME = "manifest.tsv";

    /** 覆盖层目标 harness 版本，由同一个生成脚本写出。 */
    private static final String VERSION_NAME = "harness-version.txt";

    /** 容器内 CLI 包相对 rootfs 的路径，其版本即容器里跑的 harness 版本。 */
    private static final String CLI_PACKAGE = "opt/node/lib/node_modules/@deepseek-ai/dsh";

    /**
     * 已安装包可能出现的两处位置。Node 从 CLI 包内部开始向上解析模块，因此
     * {@code @deepseek-ai/dsh/node_modules/} 下那份优先于 npm 提升到顶层的那份；
     * 两处都存在时两份都覆盖，避免依赖实际解析到哪一份。
     */
    private static final String[] PACKAGE_ROOTS = {
            "opt/node/lib/node_modules/@deepseek-ai/dsh/node_modules",
            "opt/node/lib/node_modules",
    };

    /** 取 package.json 里第一个 version 字段（顶层键，位于文件开头）。 */
    private static final Pattern VERSION_FIELD =
            Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");

    private HarnessOverlay() {
    }

    /** 清单里的一行：某个包的一个文件及其期望内容哈希。 */
    private static final class FileExpectation {
        final String path;
        final String sha256;

        FileExpectation(String path, String sha256) {
            this.path = path;
            this.sha256 = sha256;
        }
    }

    /** 清单里某个包的全部期望：目标版本 + 文件列表。 */
    private static final class PackageExpectation {
        final String name;
        final String version;
        final List<FileExpectation> files = new ArrayList<>();

        PackageExpectation(String name, String version) {
            this.name = name;
            this.version = version;
        }

        /** Android 资产目录名（去掉 @ 与 /），与生成脚本保持一致。 */
        String assetDir() {
            return name.replaceFirst("^@", "").replace("/", "-");
        }
    }

    /**
     * 把覆盖层应用到容器内的 harness 安装目录。
     *
     * <p>不抛异常：覆盖失败只反映在返回值里，调用方照常启动服务（此时行为等价于
     * 上游 npm 包）。返回多行摘要，首行是总计，后续每行说明一个包的处理结果。
     *
     * @param rootfsDir  容器 rootfs 根目录
     * @param overlayDir 已从 assets 展开到本地文件系统的覆盖层目录（含 manifest.tsv）
     * @return 供日志记录的处理摘要
     */
    public static String apply(File rootfsDir, File overlayDir) {
        try {
            Map<String, PackageExpectation> manifest = readManifest(overlayDir);
            int applied = 0;
            int current = 0;
            int failed = 0;
            List<String> notes = new ArrayList<>();
            for (PackageExpectation plan : manifest.values()) {
                List<File> targets = installedPackageDirs(rootfsDir, plan.name);
                if (targets.isEmpty()) {
                    notes.add(plan.name + ": 容器内未找到该包，跳过");
                    continue;
                }
                for (File target : targets) {
                    String where = plan.name + " @ " + relative(rootfsDir, target);
                    String installed = installedVersion(target);
                    if (installed == null) {
                        notes.add(where + ": 读不到版本，跳过");
                        continue;
                    }
                    if (!installed.equals(plan.version)) {
                        notes.add(where + ": 已安装 " + installed + " ≠ 覆盖层 " + plan.version
                                + "，跳过（保留上游行为）");
                        continue;
                    }
                    int appliedHere = 0;
                    int currentHere = 0;
                    for (FileExpectation expectation : plan.files) {
                        File source = new File(new File(overlayDir, plan.assetDir()),
                                expectation.path.replace('/', File.separatorChar));
                        File destination = new File(target, expectation.path.replace('/', File.separatorChar));
                        if (!source.isFile()) {
                            notes.add(where + ": 资产缺失 " + expectation.path);
                            failed++;
                        } else if (expectation.sha256.equals(sha256(destination))) {
                            current++;
                            currentHere++;
                        } else if (writeAtomically(source, destination)
                                && expectation.sha256.equals(sha256(destination))) {
                            applied++;
                            appliedHere++;
                        } else {
                            notes.add(where + ": 覆盖后校验失败 " + expectation.path);
                            failed++;
                        }
                    }
                    notes.add(where + ": 覆盖 " + appliedHere + " 个文件，已是最新 " + currentHere + " 个");
                }
            }
            StringBuilder summary = new StringBuilder();
            summary.append("harness overlay: 覆盖 ").append(applied)
                    .append(" 个文件，已是最新 ").append(current)
                    .append("，失败 ").append(failed)
                    .append("，清单包数 ").append(manifest.size());
            for (String note : notes) summary.append("\n  ").append(note);
            return summary.toString();
        } catch (Exception e) {
            return "harness overlay: 应用失败（保留上游 npm 包）: " + e;
        }
    }

    /**
     * 判断是否需要（重新）应用覆盖层——存在"版本匹配但内容不一致"的文件时为真。
     *
     * <p>服务启动前用它做自愈：用户在容器里重装或升级过 dsh 之后覆盖会失效；内容
     * 一致时不做任何写入，避免每次启动都碰这些文件。
     *
     * @param rootfsDir  容器 rootfs 根目录
     * @param overlayDir 已展开的覆盖层目录
     * @return 至少一个已安装包需要覆盖时为 true
     */
    public static boolean needsApply(File rootfsDir, File overlayDir) {
        try {
            for (PackageExpectation plan : readManifest(overlayDir).values()) {
                for (File target : installedPackageDirs(rootfsDir, plan.name)) {
                    String installed = installedVersion(target);
                    if (installed == null || !installed.equals(plan.version)) continue;
                    for (FileExpectation expectation : plan.files) {
                        File destination = new File(target, expectation.path.replace('/', File.separatorChar));
                        if (!expectation.sha256.equals(sha256(destination))) return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 覆盖层针对的 harness 版本（APK 内资产声明的那个版本）。
     *
     * <p>容器里的 dsh 应当装成这个版本：覆盖层只对同版本的包做过校验，装别的版本
     * 会被 {@link #apply} 整包跳过。
     *
     * @param overlayDir 已展开的覆盖层目录
     * @return 版本号；资产缺失或读不出时返回 null（调用方退回"装 latest"的旧行为）
     */
    public static String targetVersion(File overlayDir) {
        File file = new File(overlayDir, VERSION_NAME);
        if (!file.isFile()) return null;
        try {
            String version = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
            return version.isEmpty() ? null : version;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 容器内已安装的 harness 版本（CLI 包 package.json 的 version）。
     *
     * @param rootfsDir 容器 rootfs 根目录
     * @return 版本号；容器里还没装 dsh 时返回 null
     */
    public static String installedHarnessVersion(File rootfsDir) {
        return installedVersion(new File(rootfsDir, CLI_PACKAGE.replace('/', File.separatorChar)));
    }

    /** 读取覆盖层清单；没有清单（老版本资产）时返回空表。 */
    private static Map<String, PackageExpectation> readManifest(File overlayDir) throws IOException {
        Map<String, PackageExpectation> packages = new LinkedHashMap<>();
        File manifest = new File(overlayDir, MANIFEST_NAME);
        if (!manifest.isFile()) return packages;
        for (String line : Files.readAllLines(manifest.toPath(), StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            String[] fields = trimmed.split("\t");
            if (fields.length != 4) continue;
            PackageExpectation plan = packages.get(fields[0]);
            if (plan == null) {
                plan = new PackageExpectation(fields[0], fields[1]);
                packages.put(fields[0], plan);
            }
            plan.files.add(new FileExpectation(fields[2], fields[3]));
        }
        return packages;
    }

    /** 容器内实际存在该包的所有目录，按 Node 的解析优先级排列。 */
    private static List<File> installedPackageDirs(File rootfsDir, String packageName) {
        List<File> found = new ArrayList<>();
        for (String packageRoot : PACKAGE_ROOTS) {
            File candidate = new File(new File(rootfsDir, packageRoot.replace('/', File.separatorChar)),
                    packageName.replace('/', File.separatorChar));
            if (candidate.isDirectory()) found.add(candidate);
        }
        return found;
    }

    /** package.json 声明的版本；读不到时返回 null。 */
    private static String installedVersion(File packageDir) {
        File manifest = new File(packageDir, "package.json");
        if (!manifest.isFile()) return null;
        try {
            Matcher matcher = VERSION_FIELD.matcher(
                    new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8));
            return matcher.find() ? matcher.group(1) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** 先写同目录临时文件再改名，避免进程中途被杀留下半个模块文件。 */
    private static boolean writeAtomically(File source, File destination) {
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
        File staging = new File(parent, destination.getName() + ".dsh-overlay.tmp");
        try {
            copy(source, staging);
            Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            // 清掉临时文件：留着它会让下次替换与目录遍历都多一个垃圾文件；删不掉
            // 也不影响本次结果，因此不向上抛。
            if (!staging.delete()) staging.deleteOnExit();
            return false;
        }
    }

    private static void copy(File source, File destination) throws IOException {
        try (InputStream in = Files.newInputStream(source.toPath())) {
            Files.copy(in, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 文件 sha256（小写十六进制）；不存在或读取失败时返回 null。 */
    private static String sha256(File file) {
        if (file == null || !file.isFile()) return null;
        try (InputStream in = Files.newInputStream(file.toPath())) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) digest.update(buffer, 0, read);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 供日志使用的相对路径；无法相对化时退化为绝对路径。 */
    private static String relative(File root, File path) {
        String rootPath = root.getAbsolutePath();
        String absolute = path.getAbsolutePath();
        return absolute.startsWith(rootPath + File.separator)
                ? absolute.substring(rootPath.length() + 1)
                : absolute;
    }
}

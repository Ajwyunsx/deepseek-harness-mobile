package com.dshmobile.app;

import android.content.Context;

import org.apache.commons.compress.archivers.ar.ArArchiveEntry;
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 首启引导安装：下载 Ubuntu rootfs、proot、Node.js，并在容器内安装 dsh。
 * 所有方法都在调用线程执行，由调用方放到后台线程。
 */
public final class BootstrapInstaller {

    public interface Listener {
        void onStage(String stage, int percent);

        void onLog(String line);

        void onDone(boolean success, String error);
    }

    private static final String TERMUX_POOL =
            "https://mirrors.ustc.edu.cn/termux/apt/termux-main/pool/main/";
    private static final String PROOT_POOL = TERMUX_POOL + "p/proot/";
    private static final String LIBTALLOC_POOL = TERMUX_POOL + "libt/libtalloc/";
    private static final String LIBANDROID_SHMEM_POOL = TERMUX_POOL + "liba/libandroid-shmem/";
    private static final String NODE_SERIES = "latest-v22.x";
    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 60000;

    private final Context ctx;
    private final Prefs prefs;
    private final Listener listener;
    private volatile boolean cancelled;

    public BootstrapInstaller(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = Prefs.of(ctx);
        this.listener = listener;
    }

    public void cancel() {
        cancelled = true;
    }

    private void stage(String s, int p) {
        listener.onStage(s, p);
    }

    private void log(String s) {
        listener.onLog(s);
    }

    private void checkCancelled() throws IOException {
        if (cancelled) throw new IOException("已取消");
    }

    /**
     * 展开 assets 里随 APK 携带的 harness 覆盖层，覆盖容器内 npm 装好的同名包。
     *
     * <p>只打日志不抛异常：覆盖失败时容器继续用上游包（行为与不装补丁一致），
     * 不能让一个可选步骤失败掉整个安装。版本不匹配的包会被 HarnessOverlay 跳过。
     *
     * @param rootfs 容器 rootfs 根目录
     */
    private void applyHarnessOverlay(File rootfs) {
        try {
            File overlayDir = HarnessOverlayAssets.extract(ctx, new File(ctx.getCacheDir(), "harness-overlay"));
            log(HarnessOverlay.apply(rootfs, overlayDir));
        } catch (Exception e) {
            log("⚠ harness 覆盖层应用失败（继续使用上游 npm 包）: " + e);
        }
    }

    /** 执行完整安装流程。 */
    public void run() {
        try {
            File base = ProotRunner.baseDir(ctx);
            File dl = new File(base, "dl");
            //noinspection ResultOfMethodCallIgnored
            dl.mkdirs();
            File rootfs = ProotRunner.rootfsDir(ctx);

            // 0. 宿主环境检查：部分 Android 15/16 设备是 16KB 页内核，
            //    4KB 对齐的 ELF（Ubuntu/Node 官方包）在上面 exec 直接 ENOEXEC
            long pageSize = android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE);
            log("宿主内核页大小: " + pageSize + " 字节");
            if (pageSize == 16384) {
                log("⚠ 这是 16KB 页设备：Ubuntu rootfs 与 Node.js 官方二进制为 4KB 对齐，"
                        + "可能无法执行（Exec format error）；"
                        + "proot 本体用 Termux 新版构建（16KB 对齐）不受影响。");
            }

            // 1. rootfs
            File rootfsTar = new File(dl, "ubuntu-base.tar.gz");
            if (!new File(rootfs, "bin/bash").isFile()) {
                stage("下载 Ubuntu rootfs", 2);
                download(prefs.getRootfsUrl(), rootfsTar, 2, 25);
                checkCancelled();
                stage("解压 rootfs", 26);
                deleteRecursively(rootfs);
                //noinspection ResultOfMethodCallIgnored
                rootfs.mkdirs();
                extractTar(rootfsTar, rootfs, true, 0);
                log("rootfs 解压完成");
            } else {
                log("rootfs 已存在，跳过");
            }

            // 2. proot（含 loader：proot 启动任何 ELF 都需要它，编译内置路径指向
            //    Termux 私有目录，跨 uid 读取即 EACCES，必须随包提取并设 PROOT_LOADER）
            File proot = ProotRunner.prootBin(ctx);
            File loader = new File(base, "loader");
            if (!proot.isFile() || !loader.isFile()) {
                stage("下载 proot", 32);
                String debUrl = resolveTermuxDeb(PROOT_POOL, "proot_");
                log("proot 包: " + debUrl);
                File deb = new File(dl, "proot.deb");
                download(debUrl, deb, 32, 40);
                checkCancelled();
                stage("提取 proot", 41);
                extractProotFromDeb(deb, base);
                //noinspection ResultOfMethodCallIgnored
                proot.setExecutable(true, true);
                //noinspection ResultOfMethodCallIgnored
                loader.setExecutable(true, true);
                //noinspection ResultOfMethodCallIgnored
                new File(base, "loader32").setExecutable(true, true);
                log("proot 与 loader 就绪");
            } else {
                log("proot 已存在，跳过");
            }
            // 16KB 页设备上核对 proot/loader 的 ELF 对齐是否达标（Termux 新构建应为 16384）
            if (pageSize == 16384) {
                long align = elfMaxAlign(proot);
                log("proot ELF 对齐: " + align);
                if (align >= 0 && align < 16384) {
                    log("⚠ proot 二进制未按 16KB 对齐，在此设备上无法运行，请反馈此日志。");
                }
            }

            // 2b. proot 依赖库（Termux proot 动态链接 libtalloc.so.2 / libandroid-shmem.so）
            File libDir = new File(base, "lib");
            File tallocSo = new File(libDir, "libtalloc.so.2");
            if (!tallocSo.isFile()) {
                stage("下载 proot 依赖库", 42);
                //noinspection ResultOfMethodCallIgnored
                libDir.mkdirs();
                String tallocUrl = resolveTermuxDeb(LIBTALLOC_POOL, "libtalloc_");
                log("libtalloc 包: " + tallocUrl);
                File tallocDeb = new File(dl, "libtalloc.deb");
                download(tallocUrl, tallocDeb, 42, 43);
                extractLibsFromDeb(tallocDeb, libDir);
                checkCancelled();
                String shmemUrl = resolveTermuxDeb(LIBANDROID_SHMEM_POOL, "libandroid-shmem_");
                log("libandroid-shmem 包: " + shmemUrl);
                File shmemDeb = new File(dl, "libandroid-shmem.deb");
                download(shmemUrl, shmemDeb, 43, 44);
                extractLibsFromDeb(shmemDeb, libDir);
                log("proot 依赖库就绪");
            } else {
                log("proot 依赖库已存在，跳过");
            }

            // 3. Node.js -> rootfs/opt/node
            File nodeBin = new File(rootfs, "opt/node/bin/node");
            if (!nodeBin.isFile()) {
                stage("下载 Node.js", 45);
                String nodeUrl = resolveNodeUrl();
                log("Node 包: " + nodeUrl);
                File nodeTar = new File(dl, "node.tar.xz");
                download(nodeUrl, nodeTar, 45, 65);
                checkCancelled();
                stage("解压 Node.js", 66);
                deleteRecursively(new File(rootfs, "opt/node"));
                //noinspection ResultOfMethodCallIgnored
                new File(rootfs, "opt/node").mkdirs();
                extractTar(nodeTar, new File(rootfs, "opt/node"), false, 1);
                log("Node.js 就绪");
            } else {
                log("Node.js 已存在，跳过");
            }

            // 4. 容器内基础配置
            stage("配置容器", 72);
            writeContainerConfig(rootfs);

            // 4b. 编译工具链（dsh 依赖的 node-pty 需要 python3/make/g++ 现场编译）
            File gxx = new File(rootfs, "usr/bin/g++");
            if (!gxx.isFile()) {
                stage("安装编译工具链", 74);
                // 保底重试：网络/源抖动导致失败时清理重试一次，仍失败则跳过——
                // 不中断整个安装（node-pty 在服务启动前还有 HarnessService 的兜底重编）。
                boolean toolchainOk = false;
                for (int attempt = 1; attempt <= 2 && !toolchainOk && !cancelled; attempt++) {
                    try {
                        // 上次安装尝试若被系统杀掉（切后台/清理任务），容器里的 apt-get
                        // 会变孤儿继续持有 dpkg 锁，重试永远 "Could not get lock"——先清理
                        killStaleAptProcesses();
                        runInContainer(Arrays.asList("/usr/bin/apt-get",
                                "-o", "DPkg::Lock::Timeout=180", "update"), 74, 78);
                        checkCancelled();
                        // 上次若在 dpkg 配置阶段被中断，直接 install 会报
                        // "dpkg was interrupted"——先修复半配置状态（无可修复时返回 0）
                        try {
                            runInContainer(Arrays.asList("/usr/bin/dpkg", "--configure", "-a"), 78, 78);
                        } catch (Exception e) {
                            log("dpkg 状态修复未完成（忽略，继续安装）");
                        }
                        runInContainer(Arrays.asList("/usr/bin/apt-get",
                                "-o", "DPkg::Lock::Timeout=180",
                                "install", "-y", "--no-install-recommends", "python3", "make", "g++",
                                "ca-certificates", "git"), 78, 82);
                        toolchainOk = gxx.isFile();
                    } catch (Exception e) {
                        if (cancelled) throw e;
                        log("编译工具链安装失败（第 " + attempt + " 次）: " + e.getMessage());
                    }
                }
                if (!toolchainOk) {
                    log("⚠ 编译工具链安装失败，已跳过。node-pty 会在服务启动时自动重试编译；"
                            + "也可在网络好转后到设置里重置容器重装。");
                }
            } else {
                log("编译工具链已存在，跳过");
            }

            // 5. 容器内安装 dsh
            File dshBin = new File(rootfs, "opt/node/bin/dsh");
            if (!dshBin.isFile()) {
                stage("安装 DeepSeek Harness", 83);
                // 上次安装若被中断（杀进程/磁盘满），会留下半成品目录，
                // npm 重命名时报 ENOTEMPTY 永远装不上——先清掉再装
                File scopeDir = new File(rootfs, "opt/node/lib/node_modules/@deepseek-ai");
                File[] stale = scopeDir.listFiles();
                if (stale != null) {
                    for (File f : stale) {
                        if (f.getName().equals("dsh") || f.getName().startsWith(".dsh-")) {
                            deleteRecursively(f);
                        }
                    }
                }
                runInContainer(Arrays.asList("/opt/node/bin/npm", "config", "set", "registry",
                        prefs.getNpmRegistry()), 83, 85);
                checkCancelled();
                runInContainer(Arrays.asList("/opt/node/bin/npm", "install", "-g",
                        "@deepseek-ai/dsh"), 85, 96);
            } else {
                log("dsh 已安装，跳过");
            }

            // 5a. 用本仓库构建的补丁包覆盖 npm 装好的同名包：容器里的 dsh 来自
            // npm 上游发行版，本仓库对 harness 的修复（如 dsh-fs-local 的
            // createIfAbsent 无硬链接降级）只能靠覆盖带进容器。失败不阻断安装：
            // 宁可继续用上游包，也不能让整个安装流程失败。
            applyHarnessOverlay(rootfs);

            // 5b. 校验 node-pty 原生模块。npm 装 dsh 时 node-pty 要 node-gyp 现场编译，
            // 编译失败（常见原因：nodejs.org 头文件下载被墙）会被当 optional 依赖
            // 静默跳过、安装"成功"，但 dsh web 启动即崩（subprocess 插件加载
            // pty.node 失败）。此步独立于上面的 install：已装坏的环境重跑安装时
            // 也能修复；服务启动前 HarnessService 也会做同样的自检兜底。
            if (NodePtyFixer.needsFix(rootfs)) {
                if (!new File(rootfs, "usr/bin/g++").isFile()) {
                    // 工具链步骤已被跳过/失败：这里必然编译不过，不硬失败——
                    // 服务启动前 HarnessService 会带工具链重试（网络恢复后自愈）
                    log("⚠ 无编译工具链，跳过 node-pty 编译（服务启动时会自动重试）");
                } else {
                    stage("编译 node-pty 原生模块", 97);
                    log("node-pty 缺少 pty.node，正在容器内重建…");
                    File installLog = new File(ProotRunner.baseDir(ctx), "install.log");
                    if (!NodePtyFixer.fix(ctx, installLog)) {
                        throw new IOException("node-pty 原生模块重建失败，请到设置查看日志");
                    }
                    log("node-pty 原生模块就绪");
                }
            }

            checkCancelled();
            // 6. SSH 服务：本地终端（Termux / adb forward）连容器用
            ensureSshServer(rootfs);
            checkCancelled();
            stage("完成", 100);
            prefs.setSetupDone(true);
            listener.onDone(true, null);
        } catch (Exception e) {
            log("安装失败: " + e.getMessage());
            listener.onDone(false, e.getMessage());
        }
    }

    /**
     * 安装容器内 SSH 服务（幂等）：openssh-server。
     * host keys、/run/sshd、root 密码在每次启动 sshd 时确保（ProotRunner.startSshd）。
     */
    private void ensureSshServer(File rootfs) throws IOException, InterruptedException {
        if (new File(rootfs, "usr/sbin/sshd").isFile()) {
            log("SSH 服务已安装，跳过");
            return;
        }
        stage("安装 SSH 服务", 97);
        // 保底重试：失败重试一次，再失败则跳过（服务启动前还有
        // ensureSshServerInstalled 兜底补装，不至于整个安装被拖死）
        for (int attempt = 1; attempt <= 2 && !cancelled; attempt++) {
            try {
                // 与工具链安装同理：先清残留 apt 进程与 dpkg 半配置状态
                killStaleAptProcesses();
                runInContainer(Arrays.asList("/usr/bin/apt-get",
                        "-o", "DPkg::Lock::Timeout=180", "update"), 97, 97);
                try {
                    runInContainer(Arrays.asList("/usr/bin/dpkg", "--configure", "-a"), 97, 97);
                } catch (Exception e) {
                    log("dpkg 状态修复未完成（忽略，继续安装）");
                }
                runInContainer(Arrays.asList("/usr/bin/apt-get",
                        "-o", "DPkg::Lock::Timeout=180",
                        "install", "-y", "--no-install-recommends", "openssh-server"), 97, 99);
                return;
            } catch (Exception e) {
                if (cancelled) throw e;
                log("SSH 服务安装失败（第 " + attempt + " 次）: " + e.getMessage());
            }
        }
        log("⚠ SSH 服务安装失败，已跳过（服务启动时会自动重试补装）");
    }

    /**
     * 服务启动前兜底（HarnessService 调用）：老容器没有 sshd 时联网补装，
     * 失败只影响 SSH，不影响 Web 服务。日志追加到 logFile。
     */
    public static void ensureSshServerInstalled(Context ctx, File logFile) {
        if (new File(ProotRunner.rootfsDir(ctx), "usr/sbin/sshd").isFile()) return;
        BootstrapInstaller installer = new BootstrapInstaller(ctx, new Listener() {
            @Override
            public void onStage(String stage, int percent) {
                appendLog(logFile, "[ssh] " + stage);
            }

            @Override
            public void onLog(String line) {
                appendLog(logFile, "[ssh] " + line);
            }

            @Override
            public void onDone(boolean success, String error) {
            }
        });
        try {
            installer.ensureSshServer(ProotRunner.rootfsDir(ctx));
            appendLog(logFile, "[ssh] openssh-server 补装完成");
        } catch (Exception e) {
            appendLog(logFile, "[ssh] 安装失败（不影响 Web 服务）: " + e.getMessage());
        }
    }

    private static void appendLog(File f, String line) {
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(f, true);
            out.write((line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.close();
        } catch (Exception ignored) {
            // 日志写不进就算了，不影响主流程
        }
    }

    private void writeContainerConfig(File rootfs) throws IOException {
        File etc = new File(rootfs, "etc");
        //noinspection ResultOfMethodCallIgnored
        etc.mkdirs();
        File resolv = new File(etc, "resolv.conf");
        String dns = "nameserver 223.5.5.5\nnameserver 8.8.8.8\n";
        Files.write(resolv.toPath(), dns.getBytes(StandardCharsets.UTF_8));
        // apt 源（arm64 ports，中科大镜像），供安装 node-pty 编译工具链。
        // 必须用 http 而非 https：全新 ubuntu-base rootfs 里还没有 CA 证书，
        // https 握手直接失败（certificate is NOT trusted）→ 索引拉不到 →
        // 连 ca-certificates 自己都装不上（鸡生蛋）。apt 完整性靠 InRelease
        // 签名校验，http 是 Ubuntu 镜像的标准用法。
        File sources = new File(etc, "apt");
        //noinspection ResultOfMethodCallIgnored
        sources.mkdirs();
        String list = "deb http://mirrors.ustc.edu.cn/ubuntu-ports jammy main restricted universe multiverse\n"
                + "deb http://mirrors.ustc.edu.cn/ubuntu-ports jammy-updates main restricted universe multiverse\n"
                + "deb http://mirrors.ustc.edu.cn/ubuntu-ports jammy-security main restricted universe multiverse\n";
        Files.write(new File(sources, "sources.list").toPath(), list.getBytes(StandardCharsets.UTF_8));
        //noinspection ResultOfMethodCallIgnored
        new File(rootfs, "mnt/sd").mkdirs();
        //noinspection ResultOfMethodCallIgnored
        new File(rootfs, "mnt/shared").mkdirs();
        //noinspection ResultOfMethodCallIgnored
        new File(rootfs, "root").mkdirs();
        log("已写入 resolv.conf、apt 源与挂载点");
    }

    /** 在容器里执行命令，日志实时回调，并解析 npm 进度粗略推进百分比。 */
    private void runInContainer(List<String> inner, int pStart, int pEnd) throws IOException, InterruptedException {
        File logFile = new File(ProotRunner.baseDir(ctx), "install.log");
        ProcessBuilder pb = new ProcessBuilder(ProotRunner.buildCommand(ctx, inner));
        ProotRunner.applyEnv(ctx, pb);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        long lastDot = 0;
        int shown = pStart;
        try (InputStream in = p.getInputStream();
             OutputStream lf = new FileOutputStream(logFile, true)) {
            byte[] buf = new byte[4096];
            StringBuilder line = new StringBuilder();
            int n;
            while ((n = in.read(buf)) != -1) {
                lf.write(buf, 0, n);
                lf.flush();
                for (int i = 0; i < n; i++) {
                    char c = (char) buf[i];
                    if (c == '\n' || c == '\r') {
                        if (line.length() > 0) {
                            log(line.toString());
                            line.setLength(0);
                        }
                    } else if (line.length() < 300) {
                        line.append(c);
                    }
                }
                long now = System.currentTimeMillis();
                if (now - lastDot > 2000 && shown < pEnd) {
                    shown++;
                    lastDot = now;
                    stage(null, shown);
                }
            }
        }
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException("容器命令失败(" + code + "): " + inner);
        }
    }

    /**
     * 清理上次安装尝试残留的 apt-get/dpkg 进程。
     * App 被杀时容器里的 apt-get/dpkg 会变孤儿继续运行（含其 proot 宿主进程，
     * cmdline 里带 /usr/bin/apt-get 参数），长期持有 dpkg 锁。它们与 App 同 uid，
     * 可通过 /proc 找到并直接 kill。仅在 apt 步骤开始前调用——此时本次运行
     * 尚未启动任何 apt 进程，匹配到的必然是残留。
     */
    private void killStaleAptProcesses() {
        File[] entries = new File("/proc").listFiles();
        if (entries == null) return;
        int myUid = android.os.Process.myUid();
        int myPid = android.os.Process.myPid();
        boolean killed = false;
        for (File entry : entries) {
            int pid;
            try {
                pid = Integer.parseInt(entry.getName());
            } catch (NumberFormatException e) {
                continue; // 非 PID 目录
            }
            if (pid == myPid) continue;
            try {
                String status = readProcFile(new File(entry, "status"));
                if (status == null || parseUid(status) != myUid) continue;
                String cmdline = readProcFile(new File(entry, "cmdline"));
                if (cmdline == null) continue;
                if (cmdline.contains("apt-get") || cmdline.contains("/dpkg")
                        || cmdline.contains("unattended-upgrade")) {
                    log("清理残留的包管理进程 (pid " + pid + ")");
                    android.os.Process.killProcess(pid);
                    killed = true;
                }
            } catch (Exception ignored) {
                // 进程刚好退出等情况，忽略
            }
        }
        if (killed) {
            // 等进程退出、锁文件释放
            try {
                Thread.sleep(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 读 /proc 文本文件；cmdline 的 NUL 分隔符替换为空格。失败返回 null。 */
    private static String readProcFile(File f) {
        try (InputStream in = new FileInputStream(f)) {
            return new String(readAll(in), StandardCharsets.UTF_8).replace('\0', ' ');
        } catch (IOException e) {
            return null;
        }
    }

    /** 从 /proc/PID/status 解析真实 uid（"Uid:\t10081\t..."），失败返回 -1。 */
    private static int parseUid(String status) {
        for (String line : status.split("\n")) {
            if (line.startsWith("Uid:")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2) {
                    try {
                        return Integer.parseInt(parts[1]);
                    } catch (NumberFormatException e) {
                        return -1;
                    }
                }
            }
        }
        return -1;
    }

    // ---------- 下载 ----------

    private void download(String url, File dest, int pStart, int pEnd) throws IOException {
        checkCancelled();
        File tmp = new File(dest.getParentFile(), dest.getName() + ".part");
        HttpURLConnection conn = open(url);
        long total = conn.getContentLengthLong();
        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int n;
            long lastReport = 0;
            while ((n = in.read(buf)) != -1) {
                checkCancelled();
                out.write(buf, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (now - lastReport > 500) {
                    lastReport = now;
                    int p = total > 0
                            ? pStart + (int) ((pEnd - pStart) * done / total)
                            : pStart;
                    stage(null, Math.min(p, pEnd));
                    log(String.format("已下载 %.1f MB", done / 1048576.0));
                }
            }
        } finally {
            conn.disconnect();
        }
        if (!tmp.renameTo(dest)) {
            throw new IOException("无法写入 " + dest);
        }
    }

    private HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "dsh-mobile/1.0");
        int code = conn.getResponseCode();
        if (code >= 400) {
            throw new IOException("HTTP " + code + ": " + url);
        }
        return conn;
    }

    private String fetchText(String url) throws IOException {
        HttpURLConnection conn = open(url);
        StringBuilder sb = new StringBuilder();
        try (InputStream in = conn.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        } finally {
            conn.disconnect();
        }
        return sb.toString();
    }

    /** 从 Termux 仓库列表解析最新的指定包 aarch64 deb。 */
    private String resolveTermuxDeb(String pool, String prefix) throws IOException {
        String html = fetchText(pool);
        // href 可能是相对文件名（packages.termux.dev）也可能是绝对路径
        // （mirrors.ustc.edu.cn 的 fancyindex），统一取最后的文件名部分
        Matcher m = Pattern.compile("href=\"(?:[^\"]*/)?(" + Pattern.quote(prefix) + "[^\"]+_aarch64\\.deb)\"")
                .matcher(html);
        String latest = null;
        while (m.find()) {
            latest = m.group(1);
        }
        if (latest == null) {
            throw new IOException("未找到 " + prefix + " aarch64 包");
        }
        return pool + latest;
    }

    /** 解析 Node v22 最新 linux-arm64 包地址。 */
    private String resolveNodeUrl() throws IOException {
        String base = prefs.getNodeMirror();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String html = fetchText(base + "/" + NODE_SERIES + "/");
        Matcher m = Pattern.compile("(node-v22\\.\\d+\\.\\d+)-linux-arm64\\.tar\\.xz").matcher(html);
        String latest = null;
        while (m.find()) {
            latest = m.group(1);
        }
        if (latest == null) {
            throw new IOException("未找到 Node linux-arm64 包");
        }
        return base + "/" + NODE_SERIES + "/" + latest + "-linux-arm64.tar.xz";
    }

    // ---------- 解包 ----------

    /**
     * 解压 tar(.gz/.xz)。
     *
     * @param gz       true 表示 gzip，false 表示 xz
     * @param stripComps 剥掉的顶层目录层数
     */
    private void extractTar(File archive, File dest, boolean gz, int stripComps) throws IOException {
        InputStream fis = new BufferedInputStream(new FileInputStream(archive));
        InputStream cis = gz ? new GzipCompressorInputStream(fis) : new XZCompressorInputStream(fis);
        try (TarArchiveInputStream tar = new TarArchiveInputStream(cis)) {
            TarArchiveEntry e;
            while ((e = tar.getNextEntry()) != null) {
                checkCancelled();
                String name = e.getName();
                String[] parts = name.split("/");
                List<String> kept = new ArrayList<>();
                for (String part : parts) {
                    if (!part.isEmpty() && !part.equals(".")) kept.add(part);
                }
                if (kept.size() <= stripComps) continue;
                String rel = String.join("/", kept.subList(stripComps, kept.size()));
                if (rel.isEmpty() || rel.contains("..")) continue;
                File out = new File(dest, rel);
                if (e.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    out.mkdirs();
                } else if (e.isSymbolicLink()) {
                    String target = e.getLinkName();
                    File parent = out.getParentFile();
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                    // 用相对路径文本文件无法保留符号链接语义，直接创建符号链接
                    //noinspection ResultOfMethodCallIgnored
                    out.delete();
                    try {
                        Files.createSymbolicLink(out.toPath(), new File(target).toPath());
                    } catch (Exception ex) {
                        // 某些文件系统不支持符号链接时跳过（proot --link2symlink 兜底）
                        log("跳过符号链接: " + rel);
                    }
                } else if (e.isLink()) {
                    File parent = out.getParentFile();
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                    File src = new File(dest, e.getLinkName());
                    //noinspection ResultOfMethodCallIgnored
                    out.delete();
                    try {
                        Files.createLink(out.toPath(), src.toPath());
                    } catch (Exception ex) {
                        log("跳过硬链接: " + rel);
                    }
                } else {
                    File parent = out.getParentFile();
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                    try (OutputStream fos = new FileOutputStream(out)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = tar.read(buf)) != -1) {
                            fos.write(buf, 0, n);
                        }
                    }
                    int mode = e.getMode();
                    if ((mode & 0100) != 0) {
                        //noinspection ResultOfMethodCallIgnored
                        out.setExecutable(true, true);
                    }
                }
            }
        }
    }

    /** deb = ar 包，取其中 data.tar.* 里的 bin/proot 与 libexec/proot/loader(32)。 */
    private void extractProotFromDeb(File deb, File destDir) throws IOException {
        try (ArArchiveInputStream ar = new ArArchiveInputStream(
                new BufferedInputStream(new FileInputStream(deb)))) {
            ArArchiveEntry e;
            byte[] dataTar = null;
            boolean xz = true;
            while ((e = ar.getNextEntry()) != null) {
                String name = e.getName();
                if (name.startsWith("data.tar")) {
                    xz = name.endsWith(".xz");
                    dataTar = readAll(ar);
                    break;
                }
            }
            if (dataTar == null) {
                throw new IOException("deb 中未找到 data.tar");
            }
            InputStream cis = xz
                    ? new XZCompressorInputStream(new java.io.ByteArrayInputStream(dataTar))
                    : new GzipCompressorInputStream(new java.io.ByteArrayInputStream(dataTar));
            int found = 0;
            try (TarArchiveInputStream tar = new TarArchiveInputStream(cis)) {
                TarArchiveEntry te;
                while ((te = tar.getNextEntry()) != null) {
                    String name = te.getName();
                    if (te.isDirectory()) continue;
                    String outName = null;
                    if (name.endsWith("/bin/proot")) {
                        outName = "proot";
                    } else if (name.endsWith("/libexec/proot/loader")) {
                        outName = "loader";
                    } else if (name.endsWith("/libexec/proot/loader32")) {
                        outName = "loader32";
                    }
                    if (outName == null) continue;
                    try (OutputStream fos = new FileOutputStream(new File(destDir, outName))) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = tar.read(buf)) != -1) {
                            fos.write(buf, 0, n);
                        }
                    }
                    found++;
                }
            }
            if (found == 0) {
                throw new IOException("deb 中未找到 proot 二进制");
            }
        }
    }

    /** 从 deb 提取其中的 .so 库到 libDir（符号链接复制为实体文件）。 */
    private void extractLibsFromDeb(File deb, File libDir) throws IOException {
        java.util.Map<String, byte[]> files = new java.util.HashMap<>();
        java.util.Map<String, String> symlinks = new java.util.HashMap<>();
        try (ArArchiveInputStream ar = new ArArchiveInputStream(
                new BufferedInputStream(new FileInputStream(deb)))) {
            ArArchiveEntry e;
            byte[] dataTar = null;
            boolean xz = true;
            while ((e = ar.getNextEntry()) != null) {
                String name = e.getName();
                if (name.startsWith("data.tar")) {
                    xz = name.endsWith(".xz");
                    dataTar = readAll(ar);
                    break;
                }
            }
            if (dataTar == null) {
                throw new IOException("deb 中未找到 data.tar");
            }
            InputStream cis = xz
                    ? new XZCompressorInputStream(new java.io.ByteArrayInputStream(dataTar))
                    : new GzipCompressorInputStream(new java.io.ByteArrayInputStream(dataTar));
            try (TarArchiveInputStream tar = new TarArchiveInputStream(cis)) {
                TarArchiveEntry te;
                while ((te = tar.getNextEntry()) != null) {
                    String name = te.getName();
                    String base = name.substring(name.lastIndexOf('/') + 1);
                    if (!base.contains(".so")) continue;
                    if (te.isSymbolicLink()) {
                        String tgt = te.getLinkName();
                        symlinks.put(base, tgt.substring(tgt.lastIndexOf('/') + 1));
                    } else if (!te.isDirectory()) {
                        // TarArchiveInputStream 读到当前条目末尾即返回 -1
                        files.put(base, readAll(tar));
                    }
                }
            }
        }
        if (files.isEmpty()) {
            throw new IOException("deb 中未找到 .so 库");
        }
        for (java.util.Map.Entry<String, byte[]> en : files.entrySet()) {
            File out = new File(libDir, en.getKey());
            try (OutputStream fos = new FileOutputStream(out)) {
                fos.write(en.getValue());
            }
        }
        // 符号链接（如 libtalloc.so.2 -> libtalloc.so.2.4.3）复制为实体文件
        for (java.util.Map.Entry<String, String> en : symlinks.entrySet()) {
            byte[] target = files.get(en.getValue());
            if (target != null && !files.containsKey(en.getKey())) {
                File out = new File(libDir, en.getKey());
                try (OutputStream fos = new FileOutputStream(out)) {
                    fos.write(target);
                }
            }
        }
        log("已提取库: " + files.keySet());
    }

    /** InputStream.readAllBytes 需要 API 33+，minSdk 26 用手动读。 */
    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * 读取 64 位小端 ELF 程序头里的最大 p_align，用于判断二进制能否在
     * 16KB 页内核（部分 Android 15/16 设备）上 exec。读不出返回 -1。
     */
    static long elfMaxAlign(File f) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            byte[] ident = new byte[6];
            raf.readFully(ident);
            if (ident[0] != 0x7F || ident[1] != 'E' || ident[2] != 'L' || ident[3] != 'F'
                    || ident[4] != 2 /* 64 位 */ || ident[5] != 1 /* 小端 */) {
                return -1;
            }
            long phoff = readLeLong(raf, 0x20);
            int phentsize = readLeShort(raf, 0x36);
            int phnum = readLeShort(raf, 0x38);
            long max = 0;
            for (int i = 0; i < phnum; i++) {
                long align = readLeLong(raf, phoff + (long) i * phentsize + 0x30);
                if (align > max) max = align;
            }
            return max;
        } catch (Exception e) {
            return -1;
        }
    }

    private static long readLeLong(java.io.RandomAccessFile raf, long off) throws IOException {
        raf.seek(off);
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v |= ((long) raf.read() & 0xFF) << (8 * i);
        }
        return v;
    }

    private static int readLeShort(java.io.RandomAccessFile raf, long off) throws IOException {
        raf.seek(off);
        return (raf.read() & 0xFF) | ((raf.read() & 0xFF) << 8);
    }

    static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteRecursively(k);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}

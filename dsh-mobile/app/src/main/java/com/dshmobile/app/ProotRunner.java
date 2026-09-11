package com.dshmobile.app;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 组装并启动 proot 容器进程。 */
public final class ProotRunner {

    public static File baseDir(Context ctx) {
        return new File(ctx.getFilesDir(), "bootstrap");
    }

    public static File rootfsDir(Context ctx) {
        return new File(baseDir(ctx), "rootfs");
    }

    public static File prootBin(Context ctx) {
        return new File(baseDir(ctx), "proot");
    }

    /** proot 动态依赖库（libtalloc.so.2 / libandroid-shmem.so）目录。 */
    public static File libDir(Context ctx) {
        return new File(baseDir(ctx), "lib");
    }

    /** 设置 proot 进程所需的宿主侧环境变量。 */
    public static void applyEnv(Context ctx, ProcessBuilder pb) {
        pb.environment().put("PROOT_TMP_DIR", tmpDir(ctx).getAbsolutePath());
        if (libDir(ctx).isDirectory()) {
            pb.environment().put("LD_LIBRARY_PATH", libDir(ctx).getAbsolutePath());
        }
        // loader 必须显式指定：Termux proot 的编译内置路径指向 Termux 私有目录，跨 uid 不可读
        File loader = new File(baseDir(ctx), "loader");
        if (loader.isFile()) {
            pb.environment().put("PROOT_LOADER", loader.getAbsolutePath());
        }
    }

    public static File tmpDir(Context ctx) {
        File d = new File(baseDir(ctx), "tmp");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** 共享存储兜底目录，容器内 /mnt/shared。 */
    public static File sharedDir() {
        File d = new File("/sdcard/dsh-shared");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /**
     * 容器内 dsh 的干净工作目录（HOME 与 cwd）。
     * 不用 /root：安装工具链、npm 缓存、dsh 配置都会往 $HOME 写隐藏文件，
     * 且历史版本在 /root 留过杂物——工作区选择器里看起来就是"一大堆文件夹"。
     * 单独给 dsh 一个干净的 /home/dsh，SD 卡与共享目录也直接挂到它下面，
     * 打开工作区选择器只能看到 sd/ 与 shared/ 两个 purposeful 的入口。
     */
    public static File homeDir(Context ctx) {
        File d = new File(rootfsDir(ctx), "home/dsh");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /**
     * 组装 proot 命令。
     *
     * @param inner 容器内要执行的命令及其参数
     */
    public static List<String> buildCommand(Context ctx, List<String> inner) {
        Prefs prefs = Prefs.of(ctx);
        List<String> cmd = new ArrayList<>();
        cmd.add(prootBin(ctx).getAbsolutePath());
        cmd.add("--kill-on-exit");
        cmd.add("-0");
        cmd.add("--link2symlink");
        cmd.add("-r");
        cmd.add(rootfsDir(ctx).getAbsolutePath());
        cmd.add("-b");
        cmd.add("/dev");
        cmd.add("-b");
        cmd.add("/proc");
        cmd.add("-b");
        cmd.add("/sys");
        cmd.add("-b");
        cmd.add(tmpDir(ctx).getAbsolutePath() + ":/tmp");

        // 干净工作目录 /home/dsh（安装/dsh 运行都用它，/root 只留给系统）
        File home = homeDir(ctx);

        // 外置 SD 卡映射 -> /mnt/sd，同时挂进工作目录 -> /home/dsh/sd（方便传入文件）
        String sd = prefs.getSdPath();
        if (sd != null && new File(sd).isDirectory()) {
            new File(rootfsDir(ctx), "mnt/sd").mkdirs();
            cmd.add("-b");
            cmd.add(sd + ":/mnt/sd");
            new File(home, "sd").mkdirs();
            cmd.add("-b");
            cmd.add(sd + ":/home/dsh/sd");
        }
        // 共享存储兜底 -> /mnt/shared，同时 -> /home/dsh/shared
        File shared = sharedDir();
        if (shared.isDirectory()) {
            new File(rootfsDir(ctx), "mnt/shared").mkdirs();
            cmd.add("-b");
            cmd.add(shared.getAbsolutePath() + ":/mnt/shared");
            new File(home, "shared").mkdirs();
            cmd.add("-b");
            cmd.add(shared.getAbsolutePath() + ":/home/dsh/shared");
        }

        cmd.add("-w");
        cmd.add("/home/dsh");
        // 剥掉宿主侧的 LD_LIBRARY_PATH（指向 proot 依赖库，容器内无意义）
        cmd.add("/usr/bin/env");
        cmd.add("-u");
        cmd.add("LD_LIBRARY_PATH");
        cmd.add("PATH=/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        cmd.add("HOME=/home/dsh");
        cmd.add("TERM=xterm-256color");
        cmd.add("DEBIAN_FRONTEND=noninteractive");
        // 禁用 dsh 命令沙箱：手机 proot 容器里 bwrap（需 unprivileged user
        // namespaces，Android 内核通常禁用）和 Landlock（需内核 5.13+ 且启用
        // 该 LSM）基本都探测失败，workspace-write 下命令会报 SANDBOX_UNAVAILABLE
        // 拒绝执行。钉死 danger-full-access 后 bash-sandbox 不再咨询 runner 直接
        // 执行、fs-sandbox 不设围栏、approval 也不再逐条询问（dsh-base 组合里
        // sandbox-policy 与 approval 都读这个环境变量作为部署级覆盖）。
        cmd.add("DSH_PERMISSION_MODE=danger-full-access");
        cmd.addAll(inner);
        return cmd;
    }

    public static Process exec(Context ctx, List<String> inner, File logFile) throws IOException {
        ensureExecutable(ctx);
        ProcessBuilder pb = new ProcessBuilder(buildCommand(ctx, inner));
        applyEnv(ctx, pb);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
        return pb.start();
    }

    /** 以管道方式启动容器进程：调用方持有 stdin/stdout（交互式终端用），不落日志文件。 */
    public static Process execPiped(Context ctx, List<String> inner) throws IOException {
        ensureExecutable(ctx);
        ProcessBuilder pb = new ProcessBuilder(buildCommand(ctx, inner));
        applyEnv(ctx, pb);
        pb.redirectErrorStream(true);
        return pb.start();
    }

    /** 防御性处理：历史版本可能装过无执行位的 proot。 */
    private static void ensureExecutable(Context ctx) {
        File proot = prootBin(ctx);
        if (proot.isFile()) {
            try {
                android.system.Os.chmod(proot.getAbsolutePath(), 0755);
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                proot.setExecutable(true, true);
            }
        }
    }

    /** 启动 dsh web 服务进程。 */
    public static Process startWeb(Context ctx, int port, File logFile) throws IOException {
        List<String> inner = new ArrayList<>();
        inner.add("dsh");
        inner.add("web");
        inner.add("--host");
        inner.add("127.0.0.1");
        inner.add("--port");
        inner.add(String.valueOf(port));
        // dsh 0.1.5 起浏览器入口带 token 鉴权：不传 --no-open 会尝试拉起宿主
        // 默认浏览器（容器里必然失败，且日志多一行噪音）；带 token 的 URL 仍会
        // 打印到日志，由 MainActivity 解析后交给 WebView 完成 cookie 交换。
        inner.add("--no-open");
        return exec(ctx, inner, logFile);
    }

    /**
     * 启动容器内 sshd（-D 前台模式，生命周期随 proot 进程，服务停止时一起回收）。
     * 只监听 127.0.0.1：本机终端（Termux 等）直连；电脑走 adb forward。
     * host keys、/run/sshd 与 root 密码在每次启动时确保/同步（幂等）。
     */
    public static Process startSshd(Context ctx, int port, File logFile) throws IOException {
        String pw = Prefs.of(ctx).getSshPassword();
        List<String> inner = new ArrayList<>();
        inner.add("/bin/sh");
        inner.add("-c");
        // 普通用户 dsh（home=/home/dsh）：root 登录 shell 的 PATH 不带 node/npm，
        // 日常操作应用 dsh 用户；/etc/profile.d 给所有登录 shell 补 node 路径。
        inner.add("mkdir -p /run/sshd && ssh-keygen -A >/dev/null 2>&1; "
                + "id dsh >/dev/null 2>&1 || useradd -d /home/dsh -s /bin/bash dsh; "
                + "echo 'dsh:" + pw + "' | chpasswd && echo 'root:" + pw + "' | chpasswd && "
                + "printf 'export PATH=/opt/node/bin:$PATH\\n' > /etc/profile.d/dsh-node.sh && "
                + "exec /usr/sbin/sshd -D -e"
                + " -o ListenAddress=127.0.0.1:" + port
                + " -o PermitRootLogin=yes -o PasswordAuthentication=yes");
        return exec(ctx, inner, logFile);
    }
}

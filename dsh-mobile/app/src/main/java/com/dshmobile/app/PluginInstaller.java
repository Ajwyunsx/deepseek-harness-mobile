package com.dshmobile.app;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 容器内插件安装：等价于桌面命令 `dsh plugin --profile web add <pkg>`
 * （dsh 转发给 profile 目录下的 pnpm，再把声明了 dsh.bundle 的包
 *  reconciled 进 profile 的 bundles 层列表）。
 * pnpm 不随容器预装：首次安装前用 corepack enable 生成 shim
 * （corepack 随 Node 22 自带；首次跑 pnpm 会联网下载 pnpm 本体）。
 */
public final class PluginInstaller {

    /**
     * npm 包名/git/本地路径 spec 的宽松白名单，杜绝 shell 注入（我们不用 shell，
     * 双保险）。允许以 `/`、`.`、`~` 开头，以便安装共享目录里的本地 tarball
     * （例如 /home/dsh/shared/dsh-cmdgo-provider-0.4.0.tgz）。
     */
    private static final Pattern SPEC = Pattern.compile("^[a-zA-Z0-9@/.~][a-zA-Z0-9._/@:+\\-~]{0,127}$");
    private static final long TIMEOUT_MS = 10 * 60 * 1000;

    private PluginInstaller() {
    }

    public static final class Result {
        public final boolean ok;
        public final String output;

        Result(boolean ok, String output) {
            this.ok = ok;
            this.output = output;
        }
    }

    public static Result install(Context ctx, String spec) {
        if (spec == null || !SPEC.matcher(spec).matches()) {
            return new Result(false, "无效的插件包名：" + spec);
        }
        try {
            String pnpmErr = ensurePnpm(ctx);
            if (pnpmErr != null) return new Result(false, pnpmErr);
            List<String> cmd = Arrays.asList("dsh", "plugin", "--profile", "web", "add", spec);
            return run(ctx, cmd);
        } catch (Exception e) {
            return new Result(false, String.valueOf(e));
        }
    }

    /** pnpm shim 不存在时用 corepack 生成（写 rootfs/opt/node/bin，幂等）。 */
    private static String ensurePnpm(Context ctx) throws Exception {
        File pnpm = new File(ProotRunner.rootfsDir(ctx), "opt/node/bin/pnpm");
        if (pnpm.isFile()) return null;
        Result r = run(ctx, Arrays.asList("corepack", "enable"), 120_000);
        if (!r.ok) return "pnpm 初始化失败：\n" + r.output;
        return null;
    }

    private static Result run(Context ctx, List<String> inner) throws Exception {
        return run(ctx, inner, TIMEOUT_MS);
    }

    private static Result run(Context ctx, List<String> inner, long timeoutMs) throws Exception {
        Process p = ProotRunner.execPiped(ctx, inner);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try {
                InputStream in = p.getInputStream();
                byte[] chunk = new byte[4096];
                int n;
                while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);
            } catch (Exception ignored) {
            }
        }, "dsh-plugin-install-read");
        reader.setDaemon(true);
        reader.start();
        boolean done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!done) {
            // 先礼后兵：pnpm 下载/编译中可能不理 SIGTERM，强杀并确认退出，
            // 否则残留进程占着容器资源与 npm 锁（issue #9）
            p.destroy();
            try {
                if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
            reader.join(1500);
            return new Result(false, "安装超时（" + (timeoutMs / 60000) + " 分钟）\n" + tail(buf));
        }
        reader.join(3000);
        int code = p.exitValue();
        return new Result(code == 0, tail(buf));
    }

    /** 只回传末尾输出（pnpm 全量输出可能很长，Web 侧只展示尾部）。 */
    private static String tail(ByteArrayOutputStream buf) {
        String s = new String(buf.toByteArray(), StandardCharsets.UTF_8);
        return s.length() > 4000 ? s.substring(s.length() - 4000) : s;
    }
}

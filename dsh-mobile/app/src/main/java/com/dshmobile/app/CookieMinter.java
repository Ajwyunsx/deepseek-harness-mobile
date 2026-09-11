package com.dshmobile.app;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 直接为 dsh Web 会话签发浏览器 cookie。
 *
 * <p>dsh 0.1.5 的 Web 入口要求首次用进程随机 token 换取签名 cookie；token 只
 * 由 stdout 打印，抓取在真机上不够可靠。其实 cookie 的签名密钥是<b>持久化</b>
 * 的：连接插件激活时把它写进容器内 {@code $DSH_HOME/.credentials.yaml} 的
 * {@code records} 段，键为 {@code client-connection/browser-session}。App 直接
 * 读该文件，用同一算法（HMAC-SHA256）自签一个 cookie 塞进 WebView，即可完全
 * 绕开 token 抓取。
 *
 * <p>cookie 形态（对齐 dsh-client-connection 的 browser-auth）：
 * <pre>
 *   名字：dsh-auth-&lt;base64url(sha256(authority))&gt;
 *   值：  v1.&lt;base64url(JSON{version,authority,issuedAt,expiresAt})&gt;.&lt;base64url(HMAC)&gt;
 * </pre>
 * authority 是 Host 头（此处为 {@code 127.0.0.1:<port>}）。密钥缺失/格式变化时
 * 返回 null，由调用方回退到 token 交换。
 */
public final class CookieMinter {

    private static final String RECORD_KEY = "client-connection/browser-session";
    private static final String COOKIE_PREFIX = "dsh-auth-";
    private static final long MAX_AGE_MS = 29L * 24 * 60 * 60 * 1000;

    private CookieMinter() {
    }

    /** 可能存放凭据文档的候选路径（容器 HOME 解析差异时逐个尝试）。 */
    public static List<File> candidates(Context ctx) {
        List<File> list = new ArrayList<>();
        list.add(new File(ProotRunner.homeDir(ctx), ".dsh/.credentials.yaml"));
        File rootfs = ProotRunner.rootfsDir(ctx);
        list.add(new File(rootfs, "home/dsh/.credentials.yaml"));
        list.add(new File(rootfs, "root/.dsh/.credentials.yaml"));
        list.add(new File(rootfs, "root/.credentials.yaml"));
        return list;
    }

    /**
     * 为给定端口签发 dsh 浏览器会话 cookie。
     *
     * @param ctx  App 上下文（用于定位容器 rootfs）
     * @param port dsh Web 监听端口（authority 的一部分）
     * @return 可直接写入 WebView CookieManager 的 {@code name=value}；无法签发时为 null
     */
    public static String mint(Context ctx, int port) {
        byte[] secret = findSecret(ctx);
        return secret == null ? null : sign(secret, "127.0.0.1:" + port);
    }

    /** 在候选路径中找到第一个 32 字节签名密钥；找不到返回 null。 */
    public static byte[] findSecret(Context ctx) {
        for (File f : candidates(ctx)) {
            byte[] secret = readSecret(f);
            if (secret != null && secret.length == 32) return secret;
        }
        return null;
    }

    /** 读取并解码指定文件里的 browser-session 记录密钥；失败返回 null。 */
    public static byte[] readSecret(File file) {
        if (file == null || !file.isFile()) return null;
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Exception ignored) {
            return null;
        }
        String text = sb.toString();
        int key = text.indexOf(RECORD_KEY);
        if (key < 0) return null;
        // 记录块内（键之后）的第一个 secret：块式与流式 YAML 都覆盖
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("secret\\s*:\\s*[\"']?([A-Za-z0-9_\\-+/=]+)[\"']?")
                .matcher(text.substring(key));
        if (!m.find()) return null;
        try {
            return Base64.getUrlDecoder().decode(m.group(1));
        } catch (Exception e) {
            return null;
        }
    }

    /** 用签名密钥为指定 authority 生成 v1 cookie。 */
    public static String sign(byte[] secret, String authority) {
        try {
            long issuedAt = System.currentTimeMillis() - 1000;
            long expiresAt = issuedAt + MAX_AGE_MS;
            String payload = "{\"version\":1,\"authority\":\"" + authority + "\","
                    + "\"issuedAt\":" + issuedAt + ",\"expiresAt\":" + expiresAt + "}";
            String body = b64url(payload.getBytes(StandardCharsets.UTF_8));
            String sig = b64url(hmacSha256(secret, body.getBytes(StandardCharsets.UTF_8)));
            String name = COOKIE_PREFIX + b64url(sha256(authority.getBytes(StandardCharsets.UTF_8)));
            return name + "=v1." + body + "." + sig;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static String b64url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }
}

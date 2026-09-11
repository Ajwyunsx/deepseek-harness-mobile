package com.dshmobile.app;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 直接为 dsh Web 会话签发浏览器 cookie。
 *
 * <p>dsh 0.1.5 的 Web 入口要求首次用进程随机 token 换取签名 cookie；token 只
 * 由 stdout 打印，管道/日志抓取在真机上不够可靠。其实 cookie 的签名密钥是
 * <b>持久化</b>的：连接插件激活时把它写进容器内
 * {@code /home/dsh/.dsh/.credentials.yaml} 的 {@code records} 段，键为
 * {@code client-connection/browser-session}。App 直接读该文件，用同一算法
 * （HMAC-SHA256）自签一个 cookie 塞进 WebView，即可完全绕开 token 抓取。
 *
 * <p>cookie 形态（对齐 dsh-client-connection 的 browser-auth）：
 * <pre>
 *   名字：dsh-auth-&lt;base64url(sha256(authority))&gt;
 *   值：  v1.&lt;base64url(JSON{version,authority,issuedAt,expiresAt})&gt;.&lt;base64url(HMAC)&gt;
 * </pre>
 * 其中 authority 是 Host 头（此处为 {@code 127.0.0.1:<port>}）。密钥缺失/格式
 * 变化时返回 null，由调用方回退到 token 交换。
 */
public final class CookieMinter {

    private static final String RECORD_KEY = "client-connection/browser-session";
    private static final String COOKIE_PREFIX = "dsh-auth-";
    private static final long MAX_AGE_MS = 29L * 24 * 60 * 60 * 1000;

    private CookieMinter() {
    }

    /**
     * 为给定端口签发 dsh 浏览器会话 cookie。
     *
     * @param ctx  App 上下文（用于定位容器 rootfs）
     * @param port dsh Web 监听端口（authority 的一部分）
     * @return 可直接写入 WebView CookieManager 的 {@code name=value}；无法签发时为 null
     */
    public static String mint(Context ctx, int port) {
        byte[] secret = readSecret(ctx);
        if (secret == null || secret.length != 32) return null;
        try {
            String authority = "127.0.0.1:" + port;
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

    /** 读取并解码 browser-session 记录的 32 字节签名密钥。 */
    private static byte[] readSecret(Context ctx) {
        File file = new File(ProotRunner.homeDir(ctx), ".dsh/.credentials.yaml");
        if (!file.isFile()) return null;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            boolean inTarget = false;
            int keyIndent = -1;
            while ((line = r.readLine()) != null) {
                String trimmed = line.trim();
                int indent = line.length() - line.replaceFirst("^\\s*", "").length();
                if (!inTarget) {
                    // 容忍 YAML 给含 `/` 的键加引号的情况
                    if (trimmed.contains(RECORD_KEY) && trimmed.endsWith(":")) {
                        inTarget = true;
                        keyIndent = indent;
                    }
                    continue;
                }
                // 块内：找到 secret，或遇到下一个同/浅缩进的记录键即停
                if (trimmed.startsWith("secret:")) {
                    String v = trimmed.substring("secret:".length()).trim();
                    if (v.length() >= 2 && (v.startsWith("\"") || v.startsWith("'"))) {
                        v = v.substring(1, v.length() - 1);
                    }
                    return Base64.getUrlDecoder().decode(v);
                }
                if (indent <= keyIndent && trimmed.endsWith(":")) {
                    return null;
                }
            }
        } catch (Exception ignored) {
            // 文件损坏/格式变化：交回调用方回退到 token 交换
        }
        return null;
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

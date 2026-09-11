package com.dshmobile.app;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient.FileChooserParams;
import android.net.Uri;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/** 主界面：全屏 WebView 加载 dsh Web UI，就绪前显示 DeepSeek 风格启动屏。 */
public class MainActivity extends Activity {

    private WebView webView;
    private FrameLayout splash;
    private TextView splashStatus;
    private MobileUiInjector injector;
    private Handler handler;
    private volatile boolean stopPolling;
    private volatile boolean pageFailed;
    private int loadAttempts;
    private int port;
    private static final int REQ_FILE_CHOOSER = 42;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        Prefs prefs = Prefs.of(this);
        if (!prefs.isSetupDone()) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }
        port = prefs.getPort();
        handler = new Handler(Looper.getMainLooper());
        injector = new MobileUiInjector(this);

        // 权限适配：Android 12 及以下请求存储权限（/sdcard/dsh-shared 兜底映射）；
        // Android 13+（含 16）前台服务通知改为主动申请 POST_NOTIFICATIONS——
        // targetSdk 28 虽有系统兜底弹窗，但时机不可控，显式申请更可靠
        java.util.List<String> perms = new java.util.ArrayList<>();
        if (android.os.Build.VERSION.SDK_INT < 33) {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                perms.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        } else if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            perms.add(android.Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!perms.isEmpty()) {
            requestPermissions(perms.toArray(new String[0]), 1);
        }

        buildUi();
        warnIfWebViewTooOld();
        checkUpdate();
        HarnessService.startService(this);
        waitForServerAndLoad();
    }

    // ---------- 版本更新检查 ----------

    /** 进入 App 时检查 GitHub Releases 最新版本，有新版本弹更新提示。静默失败。 */
    private void checkUpdate() {
        new Thread(() -> {
            try {
                java.net.URL url = new java.net.URL(
                        "https://api.github.com/repos/Ajwyunsx/deepseek-harness-mobile/releases/latest");
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) url.openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "dsh-mobile/" + currentVersion());
                java.io.InputStream in = c.getInputStream();
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int n;
                while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);
                in.close();
                c.disconnect();
                org.json.JSONObject j = new org.json.JSONObject(buf.toString("UTF-8"));
                String tag = j.optString("tag_name", "");
                String notes = j.optString("body", "");
                handler.post(() -> maybeShowUpdate(tag, notes));
            } catch (Exception ignored) {
                // 断网/GitHub 不可达：静默跳过，不影响使用
            }
        }, "dsh-update-check").start();
    }

    private void maybeShowUpdate(String tag, String notes) {
        if (isFinishing() || tag.isEmpty()) return;
        String latest = tag.startsWith("v") ? tag.substring(1) : tag;
        if (compareVersion(latest, currentVersion()) <= 0) return;
        String body = notes == null ? "" : notes.trim();
        if (body.length() > 500) body = body.substring(0, 500) + "…";
        new android.app.AlertDialog.Builder(this)
                .setTitle("发现新版本 " + tag)
                .setMessage(body.isEmpty() ? "当前 v" + currentVersion() + "，可更新到 " + tag + "。" : body)
                .setPositiveButton("立即更新", (d, w) -> {
                    try {
                        // 固定名资产直链，浏览器下载后用户手动安装
                        startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://github.com/Ajwyunsx/deepseek-harness-mobile/releases/latest/download/dsh-mobile.apk")));
                    } catch (Exception e) {
                        android.widget.Toast.makeText(this, "无法打开下载链接", android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("稍后", null)
                .show();
    }

    /** 运行时读取自身 versionName（AGP 9 默认不生成 BuildConfig）。 */
    private String currentVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    /** 语义化版本比较：a>b 返回正数，a<b 返回负数，相等 0。 */
    private static int compareVersion(String a, String b) {
        String[] pa = a.split("\\."), pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? parseIntSafe(pa[i]) : 0;
            int y = i < pb.length ? parseIntSafe(pb[i]) : 0;
            if (x != y) return x - y;
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9].*$", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    /** 老 WebView（Chrome < 116，EMUI/旧系统常见）缺一堆现代 API，
     *  dsh 界面会报错甚至渲染进程崩溃（issue #2/#4）。polyfill 已尽力补，
     *  仍提示用户去更新内核——这是治本。 */
    private void warnIfWebViewTooOld() {
        try {
            android.content.pm.PackageInfo pi = android.webkit.WebView.getCurrentWebViewPackage();
            String v = pi == null ? null : pi.versionName;
            if (v == null) return;
            int dot = v.indexOf('.');
            int major = Integer.parseInt(dot > 0 ? v.substring(0, dot) : v);
            if (major < 116) {
                android.widget.Toast.makeText(this,
                        "浏览器内核过旧（" + v + "），dsh 界面可能报错或闪退，"
                                + "请更新 Android System WebView 或 Chrome",
                        android.widget.Toast.LENGTH_LONG).show();
            }
        } catch (Exception ignored) {
            // 版本号解析不了就不提示，不阻断主流程
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);

        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                pageFailed = false;
                injector.injectEarlyPolyfill(view);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (pageFailed) return;
                injector.inject(view);
                dismissSplash();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                // 只关心主框架：子资源失败不影响页面可用性
                if (request.isForMainFrame()) {
                    pageFailed = true;
                    scheduleReload();
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            /**
             * 网页里的 <input type="file">（dsh 附件/上传插件都走这个）在
             * Android WebView 里默认被静默吞掉——必须在这里调起系统文件
             * 选择器，否则点上传毫无反应。
             */
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    // createIntent() 自带 accept 类型与多选标志
                    startActivityForResult(params.createIntent(), REQ_FILE_CHOOSER);
                    return true;
                } catch (Exception e) {
                    // 没有能处理选取的应用等情况：归还回调避免网页侧卡死
                    fileCallback = null;
                    callback.onReceiveValue(null);
                    return true;
                }
            }
        });
        webView.addJavascriptInterface(new DshBridge(), "DshNative");
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(buildFabs());

        splash = buildSplash();
        root.addView(splash, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    /**
     * 网页 → 原生桥（inject.js 拦截网页按钮后调用）。
     * 注意：@JavascriptInterface 方法跑在 WebView 后台线程，UI 操作必须 post 回主线程。
     */
    private final class DshBridge {
        /** 设置弹窗「打开配置文件」：桌面走 xdg-open，容器里没有必失败 → 原生编辑器。 */
        @android.webkit.JavascriptInterface
        public void openConfig() {
            handler.post(() -> {
                if (!isFinishing()) {
                    startActivity(new Intent(MainActivity.this, ConfigEditorActivity.class));
                }
            });
        }

        /** 设置-插件页「添加插件」：后台线程跑 dsh plugin --profile web add，完成后回调 JS。 */
        @android.webkit.JavascriptInterface
        public void installPlugin(final String spec) {
            new Thread(() -> {
                final PluginInstaller.Result r = PluginInstaller.install(MainActivity.this, spec);
                final String json = "{\"ok\":" + r.ok + ",\"output\":"
                        + org.json.JSONObject.quote(r.output) + "}";
                handler.post(() -> {
                    if (webView != null) {
                        webView.evaluateJavascript(
                                "window.__dshOnPluginInstallResult && window.__dshOnPluginInstallResult(" + json + ")",
                                null);
                    }
                });
            }, "dsh-plugin-install").start();
        }
    }

    /** 右缘悬浮按钮组：文件管理器（上）+ 容器终端（下），半透明避免遮挡 dsh 界面。 */
    private View buildFabs() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(fab("📁", v ->
                startActivity(new Intent(this, FileManagerActivity.class))));
        View term = fab(">_", v ->
                startActivity(new Intent(this, TerminalActivity.class)));
        LinearLayout.LayoutParams tlp = (LinearLayout.LayoutParams) term.getLayoutParams();
        tlp.topMargin = Ui.dp(this, 12);
        col.addView(term, tlp);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        lp.rightMargin = Ui.dp(this, 6);
        col.setLayoutParams(lp);
        return col;
    }

    private View fab(String text, View.OnClickListener click) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(15);
        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Ui.PRIMARY);
        tv.setBackground(g);
        tv.setAlpha(0.85f);
        int size = Ui.dp(this, 44);
        tv.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        tv.setOnClickListener(click);
        return tv;
    }

    private FrameLayout buildSplash() {
        FrameLayout fl = new FrameLayout(this);
        fl.setBackgroundColor(Ui.bg(this));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        int pad = Ui.dp(this, 36);
        box.setPadding(pad, pad, pad, pad);

        // DeepSeek Harness 官方鲸鱼标（图标本身含品牌识别，不再配文字）
        // 矢量本体是黑色填充，深色模式下按文字色着色，否则黑鲸贴黑底看不见
        ImageView logo = new ImageView(this);
        Drawable d = getResources().getDrawable(R.drawable.ic_dsh_brand, null);
        logo.setImageDrawable(d);
        logo.setImageTintList(ColorStateList.valueOf(Ui.text(this)));
        int logoSize = Ui.dp(this, 96);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(logoSize, logoSize);
        box.addView(logo, llp);

        ProgressBar pb = new ProgressBar(this);
        pb.setIndeterminateTintList(ColorStateList.valueOf(Ui.PRIMARY));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = Ui.dp(this, 32);
        box.addView(pb, plp);

        splashStatus = Ui.hint(this, "正在启动容器…");
        splashStatus.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stlp = Ui.matchWrap();
        stlp.topMargin = Ui.dp(this, 16);
        box.addView(splashStatus, stlp);

        LinearLayout btns = new LinearLayout(this);
        btns.setGravity(Gravity.CENTER);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 40);

        Button settings = Ui.outlineButton(this, "设置");
        settings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        btns.addView(settings);

        // 启动失败时也能进终端排查容器（Web 服务起不来时尤其有用）
        Button term = Ui.outlineButton(this, "终端");
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 48));
        tlp.leftMargin = Ui.dp(this, 16);
        term.setLayoutParams(tlp);
        term.setOnClickListener(v ->
                startActivity(new Intent(this, TerminalActivity.class)));
        btns.addView(term);

        Button reinstall = Ui.primaryButton(this, "重新安装");
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 48));
        rlp.leftMargin = Ui.dp(this, 16);
        reinstall.setLayoutParams(rlp);
        reinstall.setOnClickListener(v ->
                startActivity(new Intent(this, SetupActivity.class)));
        btns.addView(reinstall);

        box.addView(btns, blp);

        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.gravity = Gravity.CENTER;
        fl.addView(box, flp);
        return fl;
    }

    /** 后台轮询 Web 服务（真实 HTTP 请求），就绪后加载页面；启动屏保留到页面渲染完成。 */
    private void waitForServerAndLoad() {
        stopPolling = false;
        new Thread(() -> {
            long deadline = System.currentTimeMillis() + 120_000;
            boolean up = false;
            while (!stopPolling && System.currentTimeMillis() < deadline) {
                if (httpReady(port)) {
                    up = true;
                    break;
                }
                final long left = (deadline - System.currentTimeMillis()) / 1000;
                handler.post(() -> splashStatus.setText(
                        HarnessService.isRunning()
                                ? "容器已启动，等待 Web 服务就绪… (" + left + "s)"
                                : "正在启动容器… (" + left + "s)"));
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
            // 服务已在应答后，等 dsh 把带 token 的认证 URL 刷进日志（通常同一
            // tick，宽限 5s）；轮询不到就退回干净 URL（旧 cookie 仍可能有效）。
            String auth = null;
            long urlDeadline = System.currentTimeMillis() + 5_000;
            while (auth == null && System.currentTimeMillis() < urlDeadline) {
                auth = authenticatedUrl();
                if (auth == null) {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
            final String authUrl = auth;
            final boolean ok = up;
            handler.post(() -> {
                if (isFinishing()) return;
                if (ok) {
                    splashStatus.setText("正在加载界面…");
                    loadMainUrl(authUrl);
                } else {
                    splashStatus.setText("等待超时。请到设置查看日志，或点“重新安装”。");
                }
            });
        }, "dsh-port-poll").start();
    }

    /**
     * dsh 0.1.5 起 Web 入口带 token 鉴权：进程启动时打印
     * `dsh web: http://127.0.0.1:<port>/?token=<随机值>`，根路径无 token/无
     * 有效 cookie 直接 401。此方法从 dsh-web.log 取**最后一条**本端口、带
     * token 的行，交给 WebView 首次加载；服务端校验后 303 到 `/` 并种下按
     * host:port 绑定的签名 cookie，之后同源请求自动带上。
     *
     * @return 带 token 的完整 URL；日志里还没有时返回 {@code null}。
     */
    private String authenticatedUrl() {
        File log = new File(ProotRunner.baseDir(this), "dsh-web.log");
        if (!log.isFile()) return null;
        String host = "127.0.0.1:" + port;
        String best = null;
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(log), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                int at = line.indexOf("dsh web: http");
                if (at < 0) continue;
                String s = line.substring(at + "dsh web: ".length()).trim();
                int sp = s.indexOf(' ');
                if (sp >= 0) s = s.substring(0, sp);
                if (s.contains(host) && s.contains("token=")) best = s;
            }
        } catch (Exception ignored) {
            // 日志不可读：当作没有认证 URL，退回干净根路径
        }
        return best;
    }

    private void loadMainUrl(String authUrl) {
        loadAttempts++;
        webView.loadUrl(authUrl != null ? authUrl : "http://127.0.0.1:" + port + "/");
    }

    /** 主框架加载失败：恢复启动屏提示并有界自动重试（容器可能尚在预热或刚重启）。 */
    private void scheduleReload() {
        handler.post(() -> {
            if (isFinishing()) return;
            if (loadAttempts >= 15) {
                showSplash("界面加载失败。请到设置查看日志，或点“重新安装”。");
                return;
            }
            showSplash("连接 Web 服务失败，正在重试…");
            handler.postDelayed(() -> {
                if (!isFinishing()) loadMainUrl(authenticatedUrl());
            }, 2000);
        });
    }

    private void showSplash(String text) {
        splash.animate().cancel();
        splash.setAlpha(1f);
        splash.setVisibility(View.VISIBLE);
        splashStatus.setText(text);
    }

    private void dismissSplash() {
        if (splash.getVisibility() != View.VISIBLE) return;
        splash.animate().alpha(0f).setDuration(300).withEndAction(() -> {
            splash.setVisibility(View.GONE);
            splash.setAlpha(1f);
        }).start();
    }

    /** TCP 能连上不代表 HTTP 服务就绪（proot/服务预热期会占了端口但不响应），
     *  必须拿到真实 HTTP 响应才算就绪——否则 loadUrl 打过去就是白屏。 */
    private static boolean httpReady(int port) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/").openConnection();
            conn.setConnectTimeout(800);
            conn.setReadTimeout(1500);
            conn.setInstanceFollowRedirects(false);
            conn.getResponseCode();
            return true; // 任何 HTTP 响应（含 404/302）都说明 Web 服务已在应答
        } catch (IOException e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // 从未加载成功过（url 为 null 或上次加载失败）且启动屏还在 → 重新轮询加载
        if (webView != null && splash.getVisibility() == View.VISIBLE
                && (webView.getUrl() == null || pageFailed)) {
            loadAttempts = 0;
            waitForServerAndLoad();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        stopPolling = true;
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            if (fileCallback != null) {
                Uri[] results = null;
                if (resultCode == RESULT_OK && data != null) {
                    // 多选结果在 clipData，单选在 data
                    if (data.getClipData() != null) {
                        int n = data.getClipData().getItemCount();
                        results = new Uri[n];
                        for (int i = 0; i < n; i++) {
                            results[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    } else if (data.getData() != null) {
                        results = new Uri[]{data.getData()};
                    }
                }
                fileCallback.onReceiveValue(results);
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }
}

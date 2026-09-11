# DSH 手机版（dsh-mobile）/ DSH Mobile for Android

> 本仓库是 [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) 的 fork，新增 Android 手机版（[`dsh-mobile/`](dsh-mobile/)）。上游代码保持原样，手机版相关改动都在 `dsh-mobile/` 目录内。
> This is a fork of [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) with an Android port under [`dsh-mobile/`](dsh-mobile/). Upstream code is kept untouched; everything mobile lives in that directory.

**中文**：DeepSeek Harness 的 Android 手机版。App 内置 proot + Ubuntu 22.04 容器，容器内运行 Node.js + `@deepseek-ai/dsh web`，用 WebView 加载其 Web UI 并注入移动端适配样式（触控手势、抽屉侧栏、窄屏布局），界面为 DeepSeek 同款风格；安装走国内镜像，支持容器 SSH、后台保活。

**English**: An Android port of DeepSeek Harness. The app bundles a proot + Ubuntu 22.04 container running Node.js and `@deepseek-ai/dsh web`, loads the Web UI in a WebView with mobile-optimized styles and touch gestures (edge-swipe drawer, narrow-screen layouts), and installs via China-friendly mirrors. It also adds container SSH and background keep-alive.

## 下载

见 [Releases](../../releases) 页面，下载最新 `app-release.apk` 直接安装。

## 功能

- **内置 Ubuntu 容器**：proot 运行完整 Ubuntu 22.04 (aarch64) rootfs，首启联网下载（约 200MB：rootfs + Node.js + dsh），默认全部走国内镜像（rootfs/Node/Termux/apt 用中科大源，npm 用 npmmirror）
- **干净工作目录**：dsh 的 HOME 与 cwd 是容器内独立的 `/home/dsh`（不用 /root，避免安装残留与 npm 缓存污染工作区选择器）
- **SD 卡目录映射**：外置 SD 卡 bind 到容器 `/mnt/sd` 和工作目录内 `/home/dsh/sd`；`/sdcard/dsh-shared` 兜底映射到 `/mnt/shared` 与 `/home/dsh/shared`，打开工作区即可看到 sd/ shared/ 两个入口，方便传入/传出文件
- **手机版界面**：注入的 `mobile.css` 强制 dsh Web UI 适配手机屏幕比例（弹窗不溢出、右栏/详情面板按内容三态门控——上游收起 `data-rightbar-collapsed` 时保持隐藏、轨迹事件详情面板整宽覆盖、触控目标加大、代码块横向滚动、设置弹窗单列化、消息页高度链锁死内部滚动、token/耗时统计条自动折行完整显示、悬浮提示气泡弹出 2.5 秒后自动消失）
- **侧栏非常驻**：左侧 rail 默认隐藏，左上角悬浮按钮或屏幕左缘右滑呼出覆盖式抽屉，点遮罩、抽屉上左滑或选中会话自动收起
- **DeepSeek 风格**：启动屏/安装向导/设置页均为 DeepSeek 品牌风格（品牌蓝 #4D6BFE、圆角卡片）
- **前台服务保活**：容器由前台服务持有，通知栏可查看状态/停止
- **插件自愈**：dsh 插件加载失败会拖垮整个 `dsh web`（plugin tree failed to load）。服务检测到 `failed to import loader entry <id> (<pkg>)` 时，会把这个用户插件从 profile 的 `dsh.profile.bundles` 临时摘掉再重启，保证界面能起来；插件文件保留，之后用 `dsh plugin --profile web add <兼容版本>` 重装即会重新入列。设置页的「添加插件」支持绝对路径，可直接装共享目录里的本地 tarball（如 `/home/dsh/shared/dsh-cmdgo-provider-0.4.0.tgz`）
- **浏览器 token 鉴权**：dsh 0.1.5 起 Web 入口带一次性 token（根路径无 token/无有效 cookie 直接 401）。手机版不用去抓 `dsh web` 的 stdout——会话 cookie 的签名密钥是持久化的，存在容器内 `/home/dsh/.dsh/.credentials.yaml` 的 `client-connection/browser-session` 记录里；App 直接读该密钥、用相同算法（HMAC-SHA256）自签一枚按 `host:port` 绑定的 cookie（127.0.0.1 与 localhost 各一枚）写入 WebView，再加载干净根路径。加载前会先等「带 cookie 的根路径」返回 HTTP 200（最多 120s）才加载，避开容器/插件树装配期；抓不到密钥时回退实时捕获 `dsh web` 的带 token URL 做一次交换。`dsh web` 以 `--no-open` 启动，避免容器内无效的浏览器拉起
- **容器 SSH**：openssh-server 随服务自启（仅监听 127.0.0.1:8022），本机终端/Termux 直接 `ssh dsh@127.0.0.1 -p 8022`（普通用户 dsh，登录 PATH 带 node/npm；root 同密码也可登），电脑走 `adb forward`；密码首次自动生成，设置页可查看/复制/改端口
- **命令沙箱已禁用**：容器启动时钉死 `DSH_PERMISSION_MODE=danger-full-access`（proot 里 bwrap/Landlock 基本不可用，workspace-write 会报 SANDBOX_UNAVAILABLE）；dsh 的 bash/文件写入不设围栏、不逐条询问。会话里仍可手动切回 workspace-write/read-only，但沙箱 runner 不可用时受限命令会失败

## 使用

1. 安装 APK，首次启动进入安装向导，等待下载安装完成
2. 自动进入 Web 界面（127.0.0.1:3080），按引导配置 API key 即可使用
3. 需要 SD 卡映射：设置 → 开启「所有文件访问权限」→ 选择外置 SD 目录 → 重启服务
4. 容器内访问宿主机文件：`/mnt/sd`（外置 SD）、`/mnt/shared`（共享存储 dsh-shared）；工作目录 `/home/dsh` 下的 `sd/` 与 `shared/` 是同样两个入口
5. 划卡清任务后服务被杀是 OEM 行为：到 设置 → 后台保活 里把「忽略电池优化」加入白名单，并按引导开「自启动/允许后台活动」（荣耀/MagicOS 必须），可大幅降低被杀概率
6. 注意：v1.0.7 起 dsh 的 HOME 从 /root 迁到 /home/dsh，升级后需重新填一次 API key

## 构建

环境：JDK 17+、Android SDK 35、Gradle 9.5+（自带 wrapper）。

```bash
cd dsh-mobile
./gradlew :app:assembleRelease
# 输出 app/build/outputs/apk/release/app-release.apk
```

注意：aarch64 Linux 主机（Google 只发布 x86_64 aapt2）需要 box64，构建时传 `./gradlew -Pandroid.aapt2FromMavenOverride=$PWD/tools/aapt2`（包装脚本会从 `ANDROID_HOME` 或 `local.properties` 的 sdk.dir 找 SDK）；x86_64 主机与 CI 直接构建即可。JDK 17+ 用环境默认（如需指定请自行在 gradle.properties 加 `org.gradle.java.home`）。签名配置在 `app/build.gradle.kts`（自带调试级 keystore，正式发布请更换）。

## 结构

- `BootstrapInstaller` — 下载/解压 rootfs、proot（Termux apt deb）、Node.js，容器内 `npm i -g @deepseek-ai/dsh`
- `NodePtyFixer` — node-pty 原生模块（pty.node）自检与 node-gyp 重建（安装时与服务启动前双兜底）
- `ProotRunner` — 组装 proot 命令（bind /dev /proc /sys /tmp /mnt/sd /mnt/shared）启动 `dsh web`
- `HarnessService` — 前台服务持有容器进程，退出自动重启（上限 5 次）
- `MainActivity` + `MobileUiInjector` — WebView + 注入 `assets/mobile.css` / `assets/inject.js`
- `SettingsActivity` — SD 映射、端口、下载镜像、日志、重置

## 上游

DeepSeek Harness 本体（CLI / Web UI / 插件体系）见上游仓库 [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) 及本仓库其余目录。

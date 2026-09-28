# verify-nohardlink — real-filesystem proof for the fs-local patch

`packages/fs/fs-local` 的 `createIfAbsent` 修复（issue #13）在支持硬链接的文件系统上
不可见：默认路径根本不走降级分支。这个工具在**真实文件系统**上把三种让修复生效的
条件造出来，用**容器实际加载的那个 npm 包**跑一遍，覆盖前后各测一次，因此它验证的
是行为而不是实现细节——不需要 Android 设备。

| 场景 | 覆盖前（npm 上游构建） | 覆盖后（本仓库构建） |
|---|---|---|
| A `link()` 返回成功但产出符号链接（Android 桥接层行为） | `reported-success` + **断链符号链接**（静默丢数据） | `error:FS_NOT_REGULAR_FILE` |
| B `link()` 返回 `EPERM`（支持硬链接的 FS） | `error:FS_IO_ERROR`，目标未创建 | `reported-success` + 内容正确 |
| C 真实无硬链接文件系统（vfat loop 镜像） | `error:FS_IO_ERROR` | `reported-success` + 内容正确 |

A/B 用 `LD_PRELOAD` 垫片只拦截 `link()`（`link-shim.c`），其它系统调用照常，所以
被测代码以为自己就在那种文件系统上；C 是内核真的拒绝硬链接。

## 运行

需要 Linux 或 WSL 的 root、`gcc`、`node`、`losetup`、`mkfs.vfat`，以及一份装了
`@deepseek-ai/dsh` 的 npm 树（**guest 本身不需要联网**，可以在能上网的主机上装好再共享）：

```sh
# 在能联网的主机上（Windows 主机同理，路径随后通过 /mnt/c 共享给 guest）
mkdir -p /root/fsvfy/harness-tree && cd /root/fsvfy/harness-tree
npm init -y && npm i --no-audit --no-fund --ignore-scripts @deepseek-ai/dsh@0.1.7-rc.2

# "覆盖前"的基线必须来自 npm 发布物，绝不从树里取：这个门禁每次运行都会把补丁写进
# 树里，拿树当基线会让 before/after 变成同一个构建（门禁会直接拒绝这种基线）。
mkdir -p /root/fsvfy && cd /root/fsvfy
npm pack @deepseek-ai/dsh-fs-local@0.1.7-rc.2 && tar -xzf deepseek-ai-dsh-fs-local-*.tgz
cp package/lib/index.js /root/fsvfy/lib-index.upstream.js

# 在 guest 里跑（默认树路径 /root/fsvfy/harness-tree）
bash dsh-mobile/tools/verify-nohardlink/verify.sh
```

脚本会先校验基线（不含 `isHardLinkUnavailable` 标记、且与覆盖层载荷不同）再开跑，
自己断言上面那张表，任何一条不符就以非 0 退出，同时核对"覆盖后的 sha256 == 覆盖层
清单里的 sha256"、"没有暂存目录残留"。也可用于上游合并后回归：`packages/fs/fs-local`
被上游改动时先重跑 `build-harness-overlay.mjs`，再跑这个。

配套的 Android 侧门禁是 `dsh-mobile/tools/verify-harness-overlay.mjs`（校验覆盖逻辑
与资产哈希）；两者一个管"补丁是否真的修好了行为"，一个管"补丁是否真的装得进去"。

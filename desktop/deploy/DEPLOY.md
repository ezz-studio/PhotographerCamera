# PhotographerCamera Studio — 服务器部署手册（R2 拉取模型）

把桌面端 JSON 编辑工作台部署到独立子域名 **`json.tybtool.top`**（服务器 `admin@52.220.50.185`）。
**完全不触碰 `app.tybtool.top` 的 R2 / APK**：Studio 部署包只落在 `app-update` 桶的 `studio/` 前缀，
经 R2 **S3 端点预签名 URL** 下载，不经过 Cloudflare 公网缓存，与 APK 在线更新链路零耦合。

---

## 架构

```
本机(开发)                       R2 (app-update 桶)                   服务器 (52.220.50.185)
──────────                       ────────────────────                ───────────────────────
deploy.sh                         studio/                              json.tybtool.top
  ├ 打包 tarball  ──PUT────────▶  photographer-studio-<tag>.tar.gz
  ├ 写 studio_version.json ─PUT▶  studio/studio_version.json  (发布开关)
  ├ 传 bootstrap.sh ───────PUT▶  studio/bootstrap.sh
  └ SSH ──▶  curl <BOOT_GET> ─GET▶  studio/bootstrap.sh
                 │
                 └─▶ curl <VER_GET> ─GET▶ studio_version.json
                       └─▶ curl <BUNDLE_GET> ─GET▶ tarball → 校验 sha256 → 解压
                             └─▶ setup_remote.sh → venv + pip + systemd(enable,开机自启)
```

- Studio 进程仅监听 `127.0.0.1:8765`，由 **Caddy** 反代 + basicauth 对外（不要 `--host 0.0.0.0` 裸奔）。
- 开机自启：`systemctl enable photographer-studio`（`After=network-online.target`）。

---

## 文件清单（desktop/deploy/）

| 文件 | 作用 | 在哪跑 |
|---|---|---|
| `deploy.sh` | **一键部署**：打包→上传 R2→SSH 触发安装 | 本机 |
| `r2_upload.py` | R2 预签名 PUT/GET 上传器（stdlib，无第三方依赖） | 本机 |
| `bootstrap.sh` | 服务器引导：从 R2 拉包→校验→解压→调用安装 | 服务器（被拉取执行） |
| `setup_remote.sh` | 建 venv、装依赖、注册 systemd 并开机自启 | 服务器 |
| `photographer-studio.service` | systemd 单元参考 | 服务器（由 setup_remote 生成） |
| `Caddyfile.studio` | 反代 + basicauth + TLS | 服务器 |

---

## 一键部署（推荐）

1. **DNS**：Cloudflare 给 `json.tybtool.top` 加 **A 记录 → 52.220.50.185**，橙色云开启。
2. **本机**：确认 `~/.workbuddy/r2_credentials.json` 存在（已有，app-update 桶）。
3. 改 `deploy.sh` 顶部 `SERVER` / `REMOTE_DIR`（默认已填）。
4. 本机跑：
   ```bash
   bash desktop/deploy/deploy.sh
   ```
   脚本会打包、上传 R2、SSH 到服务器完成安装并注册开机自启。
5. **服务器**：装 Caddy，用 `caddy hash-password` 生成密码哈希填进 `Caddyfile.studio` 的 `<HASH>`，
   放好 Cloudflare Origin CA 证书，`sudo systemctl reload caddy`。
6. 浏览器开 `https://json.tybtool.top/` → 输密码 → 右上「导入 Profile (JSON)」随处编辑保存。

---

## 仅更新代码（已部署过）

重新跑本机 `deploy.sh` 即可（幂等，会覆盖代码、保留 `profiles/studio` 运行数据）。

若需**脱离本机**在服务器侧更新：
```bash
# VER_URL 来自最近一次 deploy.sh 输出；失效则重跑 deploy.sh 重新生成
ssh admin@52.220.50.185 'curl -sS <BOOTSTRAP_GET_URL> | STUDIO_VERSION_URL="<VER_URL>" bash -s'
```

---

## 运维

| 操作 | 命令（服务器） |
|---|---|
| 看状态 | `sudo systemctl status photographer-studio` |
| 看日志 | `sudo journalctl -u photographer-studio -f` |
| 重启 | `sudo systemctl restart photographer-studio` |
| 停 / 关开机自启 | `sudo systemctl disable --now photographer-studio` |
| 确认开机自启已注册 | `systemctl is-enabled photographer-studio` → `enabled` |

---

## 安全边界（务必保留）

- Studio 只绑 `127.0.0.1`，公网访问必须经 Caddy basicauth。
- 部署包走 R2 S3 端点预签名 URL，**不写 `app.tybtool.top` 公网路径**，不影响 APK 下载。
- R2 对象键统一 `studio/` 前缀，与 APK（`PhotographerCamera-*.apk`、`version.json`）互不干扰。

## 依赖

`requirements.txt` 已含 `scipy`（stylefit v3 依赖）。服务器需 `python3`、`systemd`、`curl`。

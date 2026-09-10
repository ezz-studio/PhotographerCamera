# PhotographerCamera Studio — 服务器部署手册（R2 拉取 · 一句指令安装）

把桌面端 JSON 编辑工作台部署到独立子域名 **`json.tybtool.top`**（服务器 `admin@52.220.50.185`）。

**完全不触碰 `app.tybtool.top` 的 APK 下载**：Studio 部署包与安装脚本落在 `app-update` 桶的 `studio/` 前缀，
经 `app.tybtool.top`（已确认为该桶的 R2 公开访问域，对 `studio/` 前缀开放 public-read）公开下载。
APK（`PhotographerCamera-*.apk` / `version.json`）位于同桶不同 key，读取机制不变、互不影响。

---

## 安装形态（类比宝塔一键安装）

```
本机(开发)                          R2 (app-update 桶, app.tybtool.top 公开域)           服务器 (52.220.50.185)
──────────                          ──────────────────────────────────────            ───────────────────────
desktop/deploy/deploy.sh            studio/
  ├ 打包 tarball  ──PUT(public)──▶   photographer-studio-<tag>.tar.gz   (部署包, 公开)
  ├ 写 version.json ─PUT(public)▶   studio_version.json  (发布开关, 公开)
  └ 传 install.sh ───PUT(public)▶   install.sh  (安装脚本, 固定公开地址)
                                          │
服务器一句指令:                        │
  url=https://app.tybtool.top/        │
       studio/install.sh;             │
  curl -sSO $url; bash install.sh ──GET▶ install.sh ──GET▶ studio_version.json ──GET▶ tarball
                                              └─▶ 校验 sha256 → 解压 → venv → systemd(开机自启) → 健康检查
```

- Studio 进程仅监听 `127.0.0.1:8765`，由 **Caddy** 反代 + basicauth 对外（不要 `--host 0.0.0.0` 裸奔）。
- 开机自启：`systemctl enable photographer-studio`（`After=network-online.target`）。
- 升级 = 服务器重跑同一句指令（代码覆盖、venv/已保存 profile 保留）。

---

## 文件清单（desktop/deploy/）

| 文件 | 作用 | 在哪跑 |
|---|---|---|
| `deploy.sh` | **本机一键**：打包→上传 R2(公开)→打印服务器一句指令 | 本机 |
| `r2_upload.py` | R2 预签名 PUT 上传器（`--public` 走 bucket 级公开读，stdlib 无第三方依赖） | 本机 |
| `server_install.sh` | **服务器一键安装/升级脚本**（即 `install.sh` 源），由 deploy.sh 上传到 R2 公开地址 | 服务器（被一句指令拉取执行） |
| `bootstrap.sh` / `setup_remote.sh` | ⚠️ 旧版拆分脚本，`server_install.sh` 已合并二者，保留仅作参考，勿再用 | — |
| `photographer-studio.service` | systemd 单元参考（实际由 server_install.sh 生成） | 服务器 |
| `Caddyfile.studio` | 反代 + basicauth + TLS 完整站点块参考 | 服务器 |
| `DEPLOY.md` | 本文件 | — |

---

## 一句话安装

**第 1 步（本机）**：确认 `~/.workbuddy/r2_credentials.json` 存在，跑：
```bash
bash desktop/deploy/deploy.sh
```
脚本会打包、上传 R2（公开）、并打印一句**服务器安装指令**，形如：
```
url=https://app.tybtool.top/studio/install.sh; if [ -f /usr/bin/curl ]; then curl -sSO $url; else wget -O install.sh $url; fi; bash install.sh
```

**第 2 步（服务器，SSH 进去后整行粘贴）**：
```bash
url=https://app.tybtool.top/studio/install.sh; if [ -f /usr/bin/curl ]; then curl -sSO $url; else wget -O install.sh $url; fi; bash install.sh
```
可选带 Caddy basicauth（需服务器已装 caddy 且 `/etc/caddy/origin.pem`+`origin.key` 已就位）：
```bash
url=https://app.tybtool.top/studio/install.sh; if [ -f /usr/bin/curl ]; then curl -sSO $url; else wget -O install.sh $url; fi; STUDIO_CADDY_PASSWORD='你的密码' bash install.sh
```

**第 3 步（公网）**：
- Cloudflare 给 `json.tybtool.top` 加 **A 记录 → 52.220.50.185**，橙色云开启，SSL/TLS 模式 `Full`。
- 未带 `STUDIO_CADDY_PASSWORD` 的，需手动配 Caddy（参考 `Caddyfile.studio`）：把站点块写进主 `Caddyfile`（或放进 `/etc/caddy/Caddyfile.d/` 并由主配置 `import /etc/caddy/Caddyfile.d/*`），`sudo systemctl reload caddy`。
- 浏览器开 `https://json.tybtool.top/` → 输密码 → 右上「导入 Profile (JSON)」随处编辑保存。

---

## 仅更新代码（已部署过）

1. 本机重跑 `bash desktop/deploy/deploy.sh`（生成新 tag、刷新 `studio_version.json` 指向新包）。
2. 服务器重跑第 2 步那句指令即可（幂等升级，保留 venv 与 `profiles/studio` 数据）。

> 注：`install.sh` / `studio_version.json` 是固定公开地址（长期有效）；部署包每次换新 tag，由 `studio_version.json` 的 `bundle` 字段指向。
> `server_install.sh` 拉 `studio_version.json` 时会追加 `?_<时间戳>` 绕过 Cloudflare 边缘缓存（同文件名默认缓存约 186 天）。

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

- Studio 只绑 `127.0.0.1`，公网访问必须经 Caddy basicauth（不要让 `--host 0.0.0.0` 裸奔）。
- 部署包 / 安装脚本 / 版本清单走 `app.tybtool.top` 公开读（R2 `studio/` 前缀），**不经过 app.tybtool.top 的 APK 路径签名逻辑**，不影响 APK 下载。
- 编辑预览时通过 `/api/upload` 选的图会写入服务器 `studio_session/uploads/`，由守护线程每 24h 自动清理 >24h 的文件（仅清临时预览图，不影响导入的 JSON 与已保存 profile）。

## 依赖

- 本机：`python3` / venv（打包与上传用，已在 `.venv`）；`~/.workbuddy/r2_credentials.json`（app-update 桶）。
- 服务器：`python3`、`systemd`、`curl`；可选 `rsync`（提速合并更新）、`caddy`（公网反代 + basicauth）。
- `requirements.txt` 已含 `scipy`（stylefit v3 依赖）。

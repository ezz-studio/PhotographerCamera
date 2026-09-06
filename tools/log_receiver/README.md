# PhotographerCamera 远程调试日志接收服务

轻量、零依赖（仅 Python 3 标准库）的实时调试日志中心。手机 App 把调试日志
（`DebugLog` 已同步转发给 `RemoteLog`）批量 POST 到这里，开发者在浏览器里看
真机日志，无需 adb / USB。

## 服务端运行

```bash
cd tools/log_receiver
./start.sh            # 默认 8080；或 ./start.sh 9000
```

- Web 面板：`http://<服务器IP>:<端口>/`
- App 上报地址：`http://<服务器IP>:<端口>/log`
- 日志落盘：`tools/log_receiver/logs/debug_YYYY-MM-DD.log`（按天滚动，单文件 50MB 切分），
  可直接 `tail -f` 看真机日志。

防火墙需放行该端口（如 `ufw allow 8080`）。若经 Nginx 反代，把 `/log` 与 `/api/*`
与 `/` 都转发到本服务即可。

## 容量（防内存/磁盘撑爆，全部有界）

- 内存：最多 100 个 session，单 session 2 万行，全局 50 万行（超限按最旧 session 裁剪）。
- 单条日志超长自动截断（400 字符/端，1000 字符/行）。
- 单次 HTTP body 上限 4MB，畸形大包直接拒。

## App 端如何连上来

`RemoteLog.bootstrap(context)` 在 `MainActivity.onCreate` 自动执行，**打开 App 即开始上报**，
无需任何按钮。地址解析顺序：

1. `SharedPreferences`（可在 App 内「调试日志」按钮里改，重启后自动重连）
2. `android/app/src/main/assets/remote_log_endpoint.txt`（构建时写死，留空=关闭）
3. 两者都为空 → 不上报（日志仍写本地 Downloads，不影响功能）

因此「打开 app 就开始调试」只需把服务器地址填进
`assets/remote_log_endpoint.txt`（去掉注释、改成 `http://<IP>:<端口>/log`），重打包即可；
或在 App 里点右下角「调试日志」按钮填写（仅当前设备安装生效，便于临时换服务器）。

## Web 面板用法

- 左侧：设备 / session 列表（带行数、丢弃数、最后时间戳）。
- 右侧：选中某 session 后 SSE 实时滚动日志；崩溃 / 错误行红色高亮。
- 顶栏：「清空当前」「清空全部」「刷新」「自动滚动」开关。

# AnimeViewerService

AnimeViewer 的本地媒体服务（v0.14）——局域网个人媒体服务（Jellyfin-lite 级最小实现）。

**职责边界**：仅索引与播放**用户自有**的视频文件；不内置任何在线播放源、不提供资源检索/嗅探。

## 能力

| 能力 | 说明 |
|------|------|
| 媒体库扫描 | 配置目录后递归扫描 11 类视频扩展名（mp4/mkv/avi/webm/mov/m4v/ts/m2ts/wmv/flv/ogv）；ffprobe 取时长/编码/分辨率；SQLite 单文件索引（`data/media.db`）；mtime+size 增量（未变化文件跳过探测）；启动自动增量扫描 |
| 文件名识别与 Bangumi 匹配 | 服务端正则解析主流命名（`[Group] Title - 01 [1080p].mkv` / `[Group][Title][01]` / `S01E05` / `第01话` 等）；调 Bangumi v0 搜索（type=2 动画，合规自定义 UA）；归一化标题全等 → 自动绑定，否则「待确认」由人工在 Web 端确认/改绑 |
| 流式播放 | `GET /api/stream/{fileId}`：mp4/m4v/webm 走 HTTP Range 随机访问（206）；其余容器由 ffmpeg 转封装为 fMP4 流（`-c copy` 不重编码，CPU 占用极低）；转封装流不支持随机跳转，seek 由前端以 `?t=` 重拉实现（`ffmpeg -ss`） |
| 鉴权 | 首次启动生成随机 Token（控制台打印 + 写入 `data/token`）；`X-AV-Token` 请求头或 `?token=` 查询参数（`<video>` 标签无法带自定义头）；默认仅监听 127.0.0.1 |

## 环境要求

- **JDK 21**（Temurin / Corretto / Microsoft OpenJDK 均可）
- **ffmpeg + ffprobe**（可选但强烈建议）：加入 PATH 即可，或用启动参数指定完整路径。
  - 未配置 ffmpeg：mp4/m4v/webm 直连播放仍可用，mkv 等容器转封装不可用
  - 未配置 ffprobe：无法探测媒体信息与时长（文件名识别匹配不受影响）

## 启动

```bash
# 构建
mvn -s maven-settings.xml -gs maven-settings.xml -DskipTests package

# 运行（默认 127.0.0.1:8787，仅本机可访问）
java -jar target/animeviewer-service-0.14.0.jar
```

启动日志会打印配对 Token：

```
──────────────────────────────────────────────
 AnimeViewerService v0.14.0 已启动
 配对 Token: a2cbb24dd9766dbbf4dd509d7b887df1
 （同时写入文件: G:\...\data\token）
 ffmpeg: 可用 / ffprobe: 可用
 默认地址: http://127.0.0.1:8787 （局域网监听请加启动参数 --server.address=0.0.0.0）
──────────────────────────────────────────────
```

### 常用配置（启动参数 `--key=value`）

| 参数 | 默认 | 说明 |
|------|------|------|
| `--server.port` | `8787` | 监听端口 |
| `--server.address` | `127.0.0.1` | **局域网观看**（手机/平板）改为 `0.0.0.0`（配合 Token 鉴权使用） |
| `--av.data-dir` | `./data` | 数据目录（token / media.db） |
| `--av.ffmpeg-path` / `--av.ffprobe-path` | `ffmpeg` / `ffprobe` | 可执行文件路径（未入 PATH 时填完整路径） |
| `--av.bangumi.proxy-host` / `--av.bangumi.proxy-port` | 空 | api.bgm.tv 直连被重置的墙内环境填 HTTP 代理（如本机 Clash `127.0.0.1:7897`） |
| `--av.scan.auto-on-start` | `true` | 启动时自动增量扫描 |
| `--av.remux.max-concurrent` | `3` | 转封装进程并发上限 |

> 注：`maven-settings.xml` 为本工程自带的项目级 Maven 配置（覆盖全局 settings 中指向内网 Nexus 的 mirror），构建时按上面命令显式传入即可。

## 前端配对（AnimeViewer Web）

1. 设置页 →「媒体服务」→ 填服务地址（默认 `http://127.0.0.1:8787`）与 Token → 「连接测试」
2. 「媒体库管理」：添加扫描目录 / 触发扫描 / 确认匹配 / 改绑 / 解绑
3. 详情页剧集 Tab：媒体库已收录的集显示 ▶ 播放按钮（本机绑定优先）

## Windows 开机自启（可选）

方案 A（推荐，任务计划程序）：
```
schtasks /Create /TN "AnimeViewerService" /SC ONLOGON ^
  /TR "javaw -jar G:\iflow\app\AnimeViewerService\target\animeviewer-service-0.14.0.jar --server.address=0.0.0.0" /F
```

方案 B：将含启动命令的 `start-service.bat` 快捷方式放入 `shell:startup` 文件夹。

## API 一览（均需 Token，除 /api/health）

```
GET    /api/health                      免鉴权健康检查（ffmpeg/ffprobe 可用性）
GET    /api/status                      服务状态汇总（含扫描进度）
GET    /api/directories                 扫描目录列表
POST   /api/directories                 {path} 添加目录并触发扫描
DELETE /api/directories/{id}            移除目录及其索引
POST   /api/scan                        {full:boolean} 触发扫描（202）
GET    /api/scan/status                 扫描进度
GET    /api/files?dirId&state&q&limit&offset   文件索引（分页）
GET    /api/files/{id}                  文件详情
POST   /api/files/{id}/match            {subjectId, sort} 人工确认/改绑
POST   /api/files/{id}/unbind           解绑
POST   /api/files/{id}/rematch          单文件重新匹配
GET    /api/subjects/{subjectId}/files  条目维度已绑定文件（剧集 Tab 播放按钮）
GET    /api/bangumi/search?kw=          Bangumi 搜索代理（type=2）
GET    /api/bangumi/subjects/{id}/episodes  条目剧集列表（改绑对齐 sort）
GET    /api/stream/{fileId}?t=&token=   流式播放（Range 直连 / fMP4 转封装）
```

## 已知边界（如实记录）

1. **转封装流 seek**：fMP4 无索引，浏览器对超出已缓冲区间的 seek 会钳制丢弃；前端以 `?t=` 重拉（重启 `ffmpeg -ss`）实现，响应约 0.5~1s；seek 落点为不晚于目标时间的关键帧（偏差 ≤ GOP，通常 2~10s，只会提前不会错过剧情）。进度条在流元数据渐进解析完成前（数秒内）映射有偏差，随后收敛。
2. **容器 ≠ 编码**：mkv/avi/ts/wmv/flv 转封装后容器变为 fMP4，但**视频编码不变**——H.264/AAC 完全兼容；HEVC 依赖设备硬解；MPEG4 Part 2 / WMV 等浏览器不支持的编码转封装后仍不可播。
3. **局域网暴露面**：默认仅本机绑定；开 `0.0.0.0` 前请确认 Token 已配对（所有业务端点均有鉴权）。Token 经查询参数传递给 `<video>`（无法带自定义请求头），属局域网个人服务的可接受取舍。
4. 版权边界：仅索引/播放用户自有文件，无任何资源获取能力。

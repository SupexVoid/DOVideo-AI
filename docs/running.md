# 本地运行指南

## 环境要求

| 组件 | 要求 | 说明 |
| :--- | :--- | :--- |
| JDK | 21 | 后端运行环境 |
| Node.js | 22 | Vue 与 Vite 构建环境 |
| Docker | Compose v2 | 启动 MySQL、Redis、MinIO、Qdrant 与 RocketMQ |
| FFmpeg | 可在终端调用 | 音频切分与关键帧抽取 |
| Tesseract | 安装 `chi_sim` 与 `eng` | 中英文关键帧 OCR |
| yt-dlp | 可选 | 仅解析在线视频链接时需要 |

建议先确认命令均可用：

```bash
java -version
node --version
docker compose version
ffmpeg -version
tesseract --version
```

## 1. 准备配置

```bash
cp .env.example .env
```

编辑 `.env`，至少替换数据库、Redis、MinIO、Qdrant 的示例密码；使用文本模型时另设 `SILICONFLOW_API_KEY`。全新数据库中 `DB_USERNAME` 与 `MYSQL_APP_USER` 应保持一致；`MYSQL_ROOT_PASSWORD` 仅供数据库初始化使用。密钥只保存在本地 `.env`，不要提交到仓库。磁盘空间不足时，可设置 `MINIO_DATA_DIR` 和 `EDITOR_TASK_ROOT`，分别存放视频对象与编辑临时文件；不要通过降低存储保护阈值绕过空间问题。

`.env.example` 默认 LLM 为 `deepseek-ai/DeepSeek-V3.2`，实际可用性以服务商当前支持为准。`LLM_TIMEOUT_SECONDS` 默认是 `300`，用于避免长视频证据分析在模型响应尚未返回时过早超时；模型或超时配置变更后需要重启后端。

## 2. 启动中间件

```bash
./scripts/dev-up.sh
```

脚本会检查本机命令与版本、校验 Compose 配置，并等待 MySQL、Redis、MinIO、Qdrant 和 RocketMQ 启动。中间件与后端默认只监听 `127.0.0.1`，不会直接暴露到局域网；远程部署时再显式修改 `SERVER_ADDRESS` 并配置反向代理。

Windows 可在项目根目录运行 `powershell -File scripts/dev-up.ps1`。该脚本只启动中间件，模型 Key、Java 和 OCR 在启动后端及验证相应功能时另行检查。如果本机已有 Redis 占用 6379，可将 `.env` 的 `REDIS_PORT` 改为其他空闲端口，例如 6380；后端与 Compose 会读取同一配置。

MinIO [社区版已改为源码分发](https://github.com/minio/minio#source-only-distribution)，历史镜像在部分环境下无法拉取。可运行 `powershell -File scripts/dev-up.ps1 -SourceMinio`，从原配置相同的固定官方源码版本构建本地镜像，首轮需要下载 Go 构建环境与依赖。构建配置位于 `docker/minio/Dockerfile` 和 `docker-compose.source-minio.yml`；不需要本机另装 Go。当前本地环境及功能验证进度见 [实施记录](text-video-editor-validation.md)。

## 3. 启动后端

```bash
set -a
source .env
set +a

cd server
./mvnw spring-boot:run
```

后端默认地址为 `http://localhost:9090`，启动时会初始化项目所需数据表。另开终端确认服务可用：

Windows 也可在仓库根目录运行 `powershell -File scripts/dev-server.ps1 -JavaHome "你的 JDK 21 安装目录"`，脚本仅为当前进程设置 Java 和 `.env`。未填写 `SILICONFLOW_API_KEY` 时，文本模型客户端使用本地不可用地址初始化，避免向真实服务发送占位凭据；AI Agent 生成仍需配置文本模型。时间轴可独立使用百炼：设置 `EDITOR_TIMED_ASR_PROVIDER=bailian` 和 `DASHSCOPE_API_KEY`，详见 [配置说明](bailian-setup.md)。全文提取入口会优先复用自动句段；两种时间戳服务均未配置且无已完成结果时，可以导入字幕继续剪辑。

```bash
curl http://localhost:9090/health
```

成功时返回 `{"code":0,"message":"success","data":"UP"}`。

## 4. 启动前端

```bash
set -a
source .env
set +a

cd client
npm ci
npm run dev
```

浏览器访问 `http://localhost:5173`。开发环境默认通过 Vite 代理访问后端；后端地址不同时修改 `VITE_DEV_PROXY_TARGET`，前后端分开部署时再设置 `VITE_API_BASE_URL`。

只查看前端 Agent 工作台时，可以打开 `http://localhost:5173/?demo`。Demo 模式使用内置示例数据，不依赖后端服务。

## 常见问题

| 现象 | 处理方式 |
| :--- | :--- |
| 后端无法连接 MySQL 或 Redis | 运行 `docker compose --env-file .env ps`，确认服务健康且 `.env` 密码一致 |
| 页面提示无法连接后端 | 先访问 `/health`；再检查 `VITE_DEV_PROXY_TARGET` 或 `VITE_API_BASE_URL` |
| 视频解析提示命令不存在 | 确认 `ffmpeg`、`tesseract` 可在终端执行，必要时配置 `FFMPEG_DIR`、`OCR_COMMAND` |
| AI 接口返回 401 或模型不可用 | 检查 `SILICONFLOW_API_KEY` 与模型名称，修改后重启后端 |
| Maven 提示 `maven-default-http-blocker` | 在 `server` 目录执行 `./mvnw -s .mvn/central-settings.xml spring-boot:run`，临时绕过失效的用户级镜像 |

停止本地中间件：

```bash
docker compose --env-file .env down
```

该命令不会删除 `mysql/data`、`redis/data`、`minio/data`、`qdrant/data` 或 RocketMQ 命名卷。需要完全重置时请先备份，再使用 `docker compose --env-file .env down --volumes` 并手动清理这些数据目录。

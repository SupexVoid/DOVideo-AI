# 测试与复现

## 自动检查

[GitHub CI](https://github.com/SupexVoid/DOVideo-AI/actions/workflows/ci.yml) 在 push 和 pull request 时运行三个检查：

| 检查 | 内容 | 环境 |
| --- | --- | --- |
| backend | 部署配置校验、Java 测试及真实 FFmpeg 媒体回归 | JDK 21、FFmpeg / FFprobe |
| frontend | Node 测试和 Vite 生产构建 | Node.js 22 |
| extension | 浏览器侧边栏 Node 测试 | Node.js 22 |

截至 2026-10-20，最近本地完整后端测试 161 项、前端测试 76 项通过，前端生产构建通过。后端数字包含新增的 FFmpeg 参数兼容测试；历史验收文档中的 159 项是当时的结果，不是冲突。CI 徽章反映 main 最近运行状态，不是测试覆盖率。

## 重点覆盖什么

| 风险 | 对应用例入口 |
| --- | --- |
| 模型返回不存在的编号、重复或乱序选段 | `server/src/test/java/com/example/server/editor/SmartSelectionValidatorTest.java` |
| 分批失败、队列满、重启中断、任务期间视频被删除 | `server/src/test/java/com/example/server/editor/SmartSelectionServiceTest.java` |
| 导出方案快照、重复请求、版本冲突 | `server/src/test/java/com/example/server/editor/ExportServiceTest.java` |
| 真实裁剪拼接、延迟音轨、FFmpeg 新旧参数与超时清理 | `server/src/test/java/com/example/server/editor/FfmpegMediaExporterTest.java` |
| 迟到推荐、手动修改、关闭重开和保存冲突 | `client/src/smartSelection.test.js` |
| 时间轴迁移后的旧结果与检索缓存 | `server/src/test/java/com/example/server/service/AiTimedMigrationTest.java` |

## 本地执行

后端测试要求 JDK 21，并确保 `ffmpeg`、`ffprobe` 已在 PATH。常规测试使用模拟模型服务，不要求真实 API Key；媒体回归会生成临时合成素材。

```bash
# 项目根目录，Linux / macOS
cd server
./mvnw test
```

Windows 使用 `server` 目录下的 `mvnw.cmd test`。若环境限制 Mockito 动态附加，可参考[运行指南](running.md)确认 JDK；本地验证曾通过为测试 JVM 显式配置现有 Mockito javaagent 运行，不需要更换项目依赖。

```bash
# 项目根目录，新终端
cd client
npm ci
npm test
npm run build

# 项目根目录，新终端
cd extension
node --test
```

部署文件可单独检查，不启动服务：

```bash
docker compose --env-file .env.example config --quiet
```

## 真实链路验收

使用自制或有权处理的短视频，依次检查上传、带时间转写、推荐理由、人工应用、连续预览、保存重开、导出下载与成片播放。观察编号、时间、保存版本及实际视频内容是否一致。

真实调用会使用已配置账户的模型额度；不在 CI 中自动调用收费模型。现有合成短素材的结果与尚未验收的真人长对话、切点听感、OCR 等范围见[实际验收记录](ai-smart-selection-validation.md)。自动测试通过不能替代内容质量和真实使用体验验收。

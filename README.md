# DoVideo-AI

**像编辑文字一样剪视频，让 AI 帮你找到值得保留的内容。**

一段几十分钟的视频，真正需要回看或保留的内容可能只有几分钟。DoVideo-AI 把视频转成带时间的文字稿：既能围绕内容总结、学习，也能点击文字回看画面、删除整段，或描述目标让 AI 推荐片段，预览后导出 MP4。

```text
上传视频 → 自动生成时间轴文字
                ├─ 总结理解 → 结论 + 时间戳证据
                └─ 手动选段 / AI 推荐 → 预览微调 → 导出视频
```

## 三个核心设计

### 1. 一份时间轴，同时连接理解与剪辑

纯文本能总结，却不能直接剪视频。系统保留每个句段的 **ID、原文、起止时间**：拼接全文供 AI 理解，点击句段定位画面，删除句段则排除对应音视频区间。总结与剪辑复用同一份转写结果，避免重复识别与两份文字不一致。

剪辑方案引用固定字幕版本；原视频保留完整，导出使用保存后的方案快照。

### 2. AI 负责选择，后端负责确定真实剪辑点

用户输入“保留知识讲解，删除重录与操作说明”，模型返回 **句段编号和选择理由**。后端检查编号是否属于当前字幕，去重、恢复原顺序，再映射真实时间；不让模型猜时间，也不让推荐静默覆盖用户的手动修改。

长文字按句数和长度分批，保留重叠上下文；任务具有批次、时间和重试上限。推荐完成后，用户可回看、应用和微调，最终复用已有导出链路。

### 3. 把长耗时处理做成可恢复的任务

- **可靠上传：** 5 MB 分片、Redis 状态记录和断点续传，MinIO 存储视频。
- **异步与隔离：** RocketMQ 调度视频分析；转写、选段与导出采用独立有界执行器，持久化任务状态，页面关闭后可以恢复查看。
- **一致性与成本：** Redisson 锁、结果复用、限流与有限重试；新时间轴替换旧上下文时同步失效派生缓存，避免继续引用旧分析证据。
- **有证据的理解：** Planner–Executor–Critic 生成并校验结构化结果，结合时间戳证据与阶段 Checkpoint；语义检索不可用时支持本地降级。

[查看技术架构](docs/architecture.md)

## 实际效果

以下为本地真实运行截图。素材是 **37.32 秒自制合成课程视频**，百炼实际识别出 8 个句段；推荐来自真实模型调用。

### 描述目标，获得推荐与理由

输入“保留二分查找的主题介绍、前提和基本步骤”，AI 选出 3 句知识讲解，排除重录提示和剪辑操作说明。用户可以逐段回看，再决定是否应用。

![AI 智能选段：目标、推荐与理由](docs/images/ai-smart-selection.jpg)

### 按文字回看、删段，核对对应画面

点击文字定位原视频，获取该时刻的实际画面；取消保留整句段，预览时跳过对应内容。也支持手动导入 SRT/VTT。

![时间轴文字、句段删除与对应画面](docs/images/bailian-export.jpg)

### 确认方案，生成独立 MP4

推荐的 3 句总计 14.12 秒，实际导出 **14.16 秒、H.264/AAC、1280×720** 的 MP4，已下载并播放至结尾。保存后重开可继续编辑。

![方案保存与真实视频导出](docs/images/ai-smart-selection-export.jpg)

<details>
<summary>查看 AI 总结与理解</summary>

同一份文字稿也可生成总结与时间戳证据。该次真实模型分析仍有部分结论未通过 Critic，页面保留人工核验提示。

![真实 AI 总结与校验提示](docs/images/real-ai-summary.jpg)

</details>

## 技术栈与源码入口

| 模块 | 技术 | 主要位置 |
| --- | --- | --- |
| 工作台与文本编辑器 | Vue 3、Vite | `client/src/components/TextVideoEditor.vue`、`useTextVideoEditor.js` |
| 时间轴、选段与导出 | Java 21、Spring Boot、FFmpeg | `server/.../editor/` |
| 内容理解与检索 | LangChain4j、Planner–Executor–Critic、Qdrant | `server/.../service/` |
| 状态与持久化 | MySQL、MyBatis-Plus、Redis、Redisson | Mapper、Checkpoint 与任务表 |
| 分析调度与对象存储 | RocketMQ、MinIO | 消费者、存储工具与 Compose 配置 |
| 语音与文本模型 | 百炼 Paraformer、硅基流动 | 独立配置语音转写和文本模型 |

## 快速运行

需要 JDK 21、Node.js 22+、Docker Compose 和 FFmpeg。复制 `.env.example` 为 `.env`，配置服务密码；自动转写填写百炼 Key，总结和智能选段填写硅基流动 Key。

```powershell
# Windows，项目根目录：启动依赖
powershell -File scripts/dev-up.ps1 -SourceMinio

# 新终端：启动后端
powershell -File scripts/dev-server.ps1 -JavaHome "你的 JDK 21 安装目录"

# 新终端：启动前端
cd client
npm ci
npm run dev
```

访问 `http://localhost:5173`。只查看固定界面示例可打开 `/?demo`，该模式不调用模型。

[完整运行指南与排错](docs/running.md) · [百炼配置](docs/bailian-setup.md)

## 验证与边界

最近一次完整验证：**后端 159 项测试、前端 76 项测试及构建通过**。短合成素材已走通真实转写、AI 推荐、人工确认、保存、导出与播放。

当前按整句段、原顺序剪辑，会缩短句间停顿；选段仅根据文字，不能判断表情或动作。真人长对话与切点听感尚未验收；总结联调保留了 Critic 警告，OCR 分支未在本机验证。文件选择器受本机扩展权限限制，真实上传通过 HTTP 分片联调验证。

[详细验收记录](docs/ai-smart-selection-validation.md)

## 仓库结构

```text
DoVideo-AI/
├── client/                 # Vue 工作台
├── server/                 # API、分析、转写、剪辑与测试
├── docker/                 # MinIO 构建与 RocketMQ 配置
├── scripts/                # 本地启动脚本
├── docs/                   # 架构、运行指南、验收与截图
│   └── development/        # 设计、实施记录与历史笔记
├── extension/              # 可选浏览器侧边栏
├── docker-compose.yml      # 本地依赖编排
└── .env.example            # 配置模板，不含真实密钥
```

本地运行产生的数据库、缓存、对象存储数据及构建产物不纳入 Git。

## 致谢与许可

基于 [Xiaoc7r/DOVideo-AI](https://github.com/Xiaoc7r/DOVideo-AI) 继续开发，感谢其视频处理与 Video Agent 基础。扩展包括统一工作台、共享时间轴文字稿、文本剪辑与 AI 智能选段。使用 [MIT License](LICENSE)。

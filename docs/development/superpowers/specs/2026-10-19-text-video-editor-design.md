# DoVideo-AI 文本驱动视频操作：首版设计

状态：用户已确认，正在分步实施；未验收项见 docs/text-video-editor-validation.md。日期：2026-10-19。

## 目标与范围

用户已确定主线：将视频转写文本作为回看、选段、删减、预览和导出视频的入口。校园平台扩展暂缓，只在后续考虑课程名称、日期、来源链接与返回原页面。

首版以用户自行上传且可处理的视频为输入，单视频、句段级操作、保持原顺序。沿用当前浅色工作台、账号与视频归属校验。

必须交付：

1. 带真实开始/结束时间与稳定 ID 的转写段落；支持 SRT/VTT 导入，并接入经真实样例验证的时间戳 ASR。
2. 文本与原视频联动：点击定位、播放高亮、选择保留片段、整段删除/恢复。
3. 按所选片段连续预览、持久化编辑方案、重新进入后恢复。
4. 查看指定时间附近的实际视频截图；不宣称自动识别完整板书。
5. 异步裁剪并拼接为 MP4，可查看任务状态并下载真实结果。
6. 用可复现素材核对片段、音画、时长、恢复和异常路径，补充真实截图与实现记录。

不纳入首版：任意字级剪辑、自由改字后合成语音、多视频混剪、转场/配乐、自动去口误/静音、跨课程知识库、智能板书识别、线上部署与规模指标。

同济 Look 的统一认证、Cookie 搬运、课程资源抓取、扩展站点适配均不进入本次实施，见根目录 TODO.md。

## 当前事实

- Vue 3、Vite、原生 video；App.vue 管理首页与现有弹层，没有独立 Router。
- Java 21、Spring Boot 3.5.9、MyBatis-Plus、MySQL、Redis、MinIO、RocketMQ；Flyway 当前 V1—V3。
- 原转写按 60 秒切片，AliyunAsrUtils 仅取响应 text 字段；不能按字符数分配时间冒充句段对齐。
- VideoContext 已有时间窗口、OCR 与帧信息；已有证据检索和时间戳定位。这些能力需复用并明确属于原有基础。
- 原鉴权拦截 /media/** 和 /analysis/**；新增接口放在 /media/editor/**，继续执行 requireOwnedMedia。
- 当前 Git main 为 cb41155，工作区在本计划写入前干净。
- 当前 PATH 可调用 Java 17；Docker、FFmpeg、FFprobe 未出现在命令检查结果中，不能据此断言未安装。实施时先查本机已有安装与服务。

## 用户路径

上传视频 → 进入“文本剪辑” → 自动取得带时间轴转写，或导入匹配字幕 → 点击文字回看 → 勾选保留/删除整段 → 连续预览 → 保存方案 → 导出 → 下载 MP4。

没有精细时间轴时，显示“需要带时间的转写或字幕”，原有纯文本仍可阅读；不将分钟级文本伪装成精细字幕。

## 数据与语义

- TimedCue：id、startMs、endMs、text；时间统一使用整数毫秒，区间为 [startMs, endMs)。
- TranscriptDocument：id、mediaId、revision、source（SUBTITLE_IMPORT/ASR_TIMED）、durationMs、state、errorCode、cues；生成中可更新状态，完成后不可原地改变时间轴。
- EditProject：id、mediaId、transcriptId、version、keptCueIds、updatedAt；客户端保存时携带 version，冲突返回明确提示。
- ClipRange：startMs、endMs；后端根据字幕版本和 keptCueIds 生成，客户端不能指定任意素材路径。
- ExportJob：id、userId、projectId、projectVersion、requestHash、state、resultObject、errorCode、createdAt、updatedAt。

重新导入字幕产生新 TranscriptDocument，旧方案仍引用旧版本。首版不做文字自由改写。重复选中同一 cue 只计算一次；重叠/相接区间在导出计划中合并，按原视频时间排序，不重复输出画面。空选段允许保存草稿，禁止预览/导出并显示原因。

字幕数量、文本长度、请求体大小、视频时长与片段范围均有服务端验证。SRT/VTT 导入保留文本，过滤字幕样式标签；正文以 Vue 文本节点显示，不直接注入 HTML。字幕格式解析与时间越界错误返回可读提示。

## 时间戳 ASR 决策

提供独立 TimedAsrProvider，输出 List<TimedCue>。不得破坏原 audioToText 接口及总结/Agent 链路。

先检查已配置服务的官方能力与真实响应。优先适配可配置 HTTP 时间戳服务；它必须提供可核验的 segments/start/end/text，时间单位在适配层明确转换。配置缺失、仅返回 text 或时间戳非法时明确报不可用，允许导入字幕继续工作。

如果现有服务不支持，不自行假定支持，也不擅自购买模型服务；提出一个可用替代与所需配置。没有真实时间戳 ASR 成功样例时，该项保持未验收，不能把字幕导入结果记为 ASR 完成。

## 后端与任务

新增 editor 包承载模块，避免继续向既有 AnalysisController 和分析工作区堆逻辑。数据库用 V4 增加转写文档、编辑方案、导出任务三张表；必要元数据可采用 JSON 字段，所有写入先验证。

接口统一前缀 /media/editor：

- POST /transcripts/import：mediaId、format、content，返回 TranscriptDocument。
- POST /transcripts/transcribe：mediaId，受理时间戳转写；GET /transcripts/status?mediaId 查询状态与完成的文档。
- GET /transcripts?mediaId：取得当前时间轴，不触发模型调用；可加 transcriptId 读取该视频旧方案引用的不可变版本，仍校验视频及文档归属。
- POST /projects：创建/保存方案；请求携带 id（创建时可缺省）、mediaId、transcriptId、version、keptCueIds。
- GET /projects?mediaId：恢复自己的最近方案。
- GET /frame?mediaId&atMs：返回授权视频在指定位置的 JPEG；前端通过带鉴权的 fetch 取得 Blob，关闭时释放 URL。
- POST /exports：projectId、projectVersion、preset；创建或返回同方案的已有导出任务。
- GET /exports/{jobId}：自己的任务状态；GET /exports/{jobId}/download：完成后取得短期下载地址。

沿用 Result 封装与 AuthService.REQUEST_USER_ID；每个读取、帧图、任务及下载接口均检查归属。新代码不接收任意文件路径、shell 参数或下载 URL。

首版采用单实例、专用有界导出执行器，默认 1 个转码 worker 与 4 个排队位置，避免与原 AI 线程池争抢。状态与方案快照保存在 MySQL，页面约每 2 秒查询当前任务；不新建 RocketMQ 主题或 SSE 协议。原有 MQ/SSE 保持原用法。

任务指纹包含用户、源视频版本/内容标识、字幕版本、选段区间、导出参数。唯一约束与状态条件更新防止并发重复导出。排队满时显式失败；执行超时终止子进程；单实例重启将中断任务标为失败并允许重试，不承诺自动断点恢复转码。失败重试复用任务记录并增加 attempt，完成的相同任务直接复用结果。

事务提交后再派发任务。导出使用请求时的不可变方案快照，用户后续改方案不影响运行中的任务。

## 媒体处理

使用 FFprobe 验证实际时长、音视频流、分辨率与帧率；原文件复制到独立任务临时目录，复用已有 MinIO 读取能力。只接收本人拥有的视频。

FFmpeg 使用 ProcessBuilder 参数数组，按区间对视频 trim、音频 atrim，重置时间戳并 concat；首版统一 H.264/AAC MP4，存在无音轨时走无音轨分支。不把任意切点的 stream copy 当作精确剪辑。

输出先在本地完成校验，再保存到 MinIO、记录稳定对象键；下载时现签地址。失败时清理本任务临时文件与不完整输出，保留原素材。Windows 清理前校验绝对路径位于本模块任务目录内。

预览是在原视频上按区间跳播，与最终重新编码的导出分别验收；不承诺浏览器跳播无缝。签名播放地址失效时重新取得地址并恢复位置。

## 前端结构

- components/TextVideoEditor.vue：单一编辑工作区，沿用浅色工作台风格。
- editorApi.js：新增接口调用，复用 apiRequest。
- useTextVideoEditor.js：加载/保存、选段、播放同步、导出状态与生命周期清理。
- clipRanges.js：可纯函数测试的区间计算。
- textVideoEditor.css：编辑器局部布局，保留现有全局 tokens。

从原资料条目增加真实“文本剪辑”入口；不替换原转写和 Video Agent。界面展示原视频、时间轴文本、选择统计、预览/保存/导出和状态。窄屏垂直排列，快捷键不覆盖输入操作。

## 验收

- 字幕导入：SRT/VTT、BOM、毫秒、多行、非法时间、越界、重叠与脚本字符串。
- 区间：全选/空选/删除中段/重复 ID/未知 ID/旧字幕版本；原顺序与重叠合并一致。
- 权限：账号 A 无法读取或导出账号 B 的字幕、方案、帧图、任务与结果。
- 真实媒体：生成红/绿/蓝各 2 秒的 6 秒测试视频，删除中段后导出约 4 秒；FFprobe 时长误差不超过 200ms，抽帧验证顺序为红→蓝。另用可处理的口播素材试听切点及音画同步。
- ASR：至少一个真实音频样例，人工核对若干句段；记录时间误差与手调需求，不编造准确率。
- 任务：重复点击、队列满、FFmpeg 不可用、处理失败、执行超时、重启中断、旧方案快照、签名过期。
- 回归：现有前端测试、前端构建、后端测试；浏览器检查桌面/手机、保存恢复与真实导出。
- 文档：真实截图、实际支持能力与未验收项；保留许可证、版权和来源。

## 执行约束

只实施以上首版，常规技术细节自主处理。需要模型 Key、已有软件的人工启动、重启系统或额外付费时再集中告知用户。未经新的明确要求，不自动提交、推送或部署。

首版后端修改属于用户当前要求的新增功能；PRODUCT.md 中“仅视觉、不修改后端”是上一轮改版范围，实施时按本设计更新该上下文，不能将其误作永久禁止。

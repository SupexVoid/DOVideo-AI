# 文本驱动视频操作 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 用户只要求当前对话推进时采用会话内执行；不自动提交、推送或部署。

**Goal:** 在已有 DoVideo-AI 中完成句段级文字定位、选段预览、可恢复编辑、真实截图和 MP4 导出。

**Architecture:** 新增独立 editor 模块，时间轴和原视频保持不可变，编辑方案仅记录保留的 cue ID。后端验证并转换为视频区间，在专用有界执行器中运行 FFmpeg；状态持久化，前端轮询。字幕导入先贯通主流程，时间戳 ASR 单独验证并接入。

**Tech Stack:** Vue 3、现有 Vite 与 apiRequest；Java 21、Spring Boot 3.5.9、MyBatis-Plus、MySQL/Flyway、现有 MinIO、FFmpeg/FFprobe；JUnit/Mockito、Node 内置测试。

**Spec:** [首版设计](../specs/2026-10-19-text-video-editor-design.md)

## Global Constraints

- 单视频、句段级操作、保持原顺序；不增加复杂多轨编辑器或组件库。
- 原转写、AI 总结、鉴权、上传、MQ 与 SSE 流程保留。
- 新接口位于 `/media/editor/**`，每个入口校验视频/方案/任务归属。
- 不按字数分配时间，不让大模型猜时间，不将字幕导入成果标为 ASR 成果。
- 同济平台工作明确延期，只保留根目录 TODO.md 中的轻量来源关联。
- 不自动提交、推送或部署；以下测试与实现步骤中的保存仅指文件保存。
- 缺失的 Key、额外付费、系统重启或确需人工启动软件时集中告知用户；不能将未完成依赖标为验收通过。

## 仓库与文件组织

在实施开始时复查 AGENTS.md、Git 状态和现有 worktree；优先复用合适工作区。对当前 main 的已发布版本建立明确基线，避免混入无关文件。

以下服务端路径均相对 `server/src/main/java/com/example/server/editor/`，测试在 `server/src/test/java/com/example/server/editor/`，沿用现有项目包名。

- `EditorModels.java`：TimedCue、TranscriptDocument、ClipRange 与保存/导出请求及响应 DTO。
- `SubtitleParser.java`：SRT/VTT 转为校验后的 cue；不调用模型。
- `ClipRangePlanner.java`：cue ID 转为有序、不重复区间。
- `EditorStore.java` + `EditorMapper.java`：转写、方案和任务持久化；唯一约束、版本条件更新。
- `EditorService.java`：归属验证、字幕导入、保存恢复和时间范围验证。
- `EditorController.java`：已有 Result/认证风格下的新增 REST 接口。
- `TimedAsrProvider.java`、`HttpTimedAsrProvider.java`、`TimedTranscriptionService.java`：真实时间轴 ASR 与任务状态。
- `FfmpegMediaTools.java`：FFprobe、帧图与 ProcessBuilder 安全执行/清理。
- `FfmpegMediaExporter.java`：根据区间实际裁剪、拼接与输出探测。
- `ExportService.java`、`ExportExecutorConfig.java`：方案快照、去重、后台状态、重试和下载。

前端新增 `client/src/components/TextVideoEditor.vue`、`client/src/useTextVideoEditor.js`、`client/src/editorApi.js`、`client/src/clipRanges.js`、`client/src/textVideoEditor.css`；App.vue 仅负责入口及打开/关闭编辑器。

## Task 1: 跑通环境与建立基线

**Files:** 读取 `README.md`、`docker-compose.yml`、`.env.example`、`server/pom.xml`；记录到新建 `docs/text-video-editor-validation.md`。实施时更新 PRODUCT.md，把上一轮“仅视觉”约束改为本次首版范围。

**Interfaces:** 产出本地运行命令、实际可用软件版本、后端健康检查及一段可播放的自有测试视频；不更改系统默认 Java。

- [x] 检查已安装的 JDK、Docker/已有数据库服务、FFmpeg 与 FFprobe，优先复用；必要时使用项目专用 Java 21 和工具路径。不要因 PATH 查不到命令直接重复安装。
- [x] 运行基线命令并记录真实退出结果：

```powershell
# client
npm test
npm run build
# server，使用 Java 21 的进程环境
.\mvnw.cmd test
```

- [x] 使用 `.env.example` 配置本地必要服务，真实密码/Key 仅放被忽略的 `.env`。不把用户 Cookie 当作服务端公共配置。
- [x] 在环境允许时启动后端，检查 `/health`；真实注册/登录、上传一段可处理视频、通过已有 `/media/playback` 播放。
- [x] 如果外部环境尚缺失，继续进行不依赖服务的模型、解析器与测试工作；将端到端启动保持为尚未完成的验收项。

**完成标志：** 基线结果已记录，知道哪些服务实际可用；没有把旧 demo 当真实业务结果。

## Task 2: 字幕解析与选段算法（先打通确定性核心）

**Files:** 新建 `EditorModels.java`、`SubtitleParser.java`、`ClipRangePlanner.java` 与同名测试；新建 `client/src/clipRanges.js`、`client/src/clipRanges.test.js`。

**Interfaces:**

```java
public record TimedCue(String id, long startMs, long endMs, String text) {}
public record ClipRange(long startMs, long endMs) {}
// SubtitleParser
List<TimedCue> parse(String format, String content, long durationMs);
// ClipRangePlanner
List<ClipRange> plan(List<TimedCue> cues, List<String> keptCueIds, long durationMs);
```

DTO 放在 EditorModels 内作为 public static record，服务与测试明确导入对应嵌套类型，避免一份 Java 文件包含多个 public 顶级类型。

- [x] 先写失败测试，验证删除中段后保持顺序、相邻/重叠选段合并：

```java
@Test void deletingMiddleCuePreservesTheTwoRemainingRanges() {
    var cues = List.of(
        new TimedCue("a", 0, 2000, "第一段"),
        new TimedCue("b", 2000, 4000, "口误"),
        new TimedCue("c", 4000, 6000, "第三段"));
    assertEquals(List.of(new ClipRange(0, 2000), new ClipRange(4000, 6000)),
        new ClipRangePlanner().plan(cues, List.of("c", "a", "a"), 6000));
}
```

- [x] 在 server 目录运行 `.\mvnw.cmd -Dtest=ClipRangePlannerTest,SubtitleParserTest test`，确认新增断言因尚未实现而失败。
- [x] 实现 SRT/VTT 时间解析、BOM 与多行文本处理、稳定 cue ID；使用字符与数字解析而非把任意字符串交给脚本执行。格式只允许 srt/vtt，正文上限 1MiB，单文档最多 10000 个 cue。
- [x] 验证 `0 <= startMs < endMs <= durationMs`、非空文本、ID 唯一；不丢弃时间无效 cue 后偷偷继续，返回具体出错条目。
- [x] 实现 keptCueIds 去重、未知 ID 拒绝、原顺序排序与区间并集；空选段返回空区间，由预览/导出入口禁止执行。
- [x] 前端实现同样的区间规则，首先用 Node 测试同一组样例：

```js
import test from 'node:test'
import assert from 'node:assert/strict'
import { buildClipRanges } from './clipRanges.js'

test('the preview skips a deleted middle cue', () => {
  const cues = [
    { id: 'a', startMs: 0, endMs: 2000 },
    { id: 'b', startMs: 2000, endMs: 4000 },
    { id: 'c', startMs: 4000, endMs: 6000 }
  ]
  assert.deepEqual(buildClipRanges(cues, ['a', 'c'], 6000), [
    { startMs: 0, endMs: 2000 }, { startMs: 4000, endMs: 6000 }
  ])
})
```

- [x] 跑通上述测试，并补充非法时间、重复/未知 ID、重叠、全删与标签文本的断言。

**完成标志：** 同一份字幕和选择结果，在前后端产生相同的区间；这一步无需模型 Key。

## Task 3: 字幕文档与编辑方案 API

**Files:** 新建 `server/src/main/resources/db/migration/V4__add_text_video_editor.sql`，`EditorStore.java`、`EditorMapper.java`、`EditorService.java`、`EditorController.java` 及对应服务/控制器测试。

**Interfaces:** 实现设计文档中的 import、GET transcripts、POST/GET projects；产生后续前端使用的 `TranscriptDocument`、`EditProject`。所有时间取自真实 FFprobe 探测的源视频，不信任客户端的 durationMs。

- [x] 先写测试：用户 A 访问 B 视频的导入/读取/保存必须失败；非法 cue ID 不写库；保存旧 version 不覆盖新方案；重新导入字幕不改变旧文档。
- [x] 运行 `./mvnw.cmd -Dtest=EditorServiceTest,EditorControllerTest test`，确认失败源于缺失行为。
- [x] V4 建立 `media_transcripts`、`media_edit_projects`、`media_export_jobs`：转写与方案使用 JSON 保存经过验证的 cue/ID 数组；导出任务包含不可变快照与用户级 requestHash 唯一索引。
- [x] 沿用 MyBatis-Plus 与 Jackson，不引入新 ORM。转写状态包含 QUEUED/PROCESSING/COMPLETED/FAILED，完成后的内容不原地修改。
- [x] 把控制器置于 `/media/editor`。每个媒体入口调用 `requireOwnedMedia(mediaId, userId)`，方案/任务则先按 userId 读取再关联检查原视频；保持现有 Result 风格，不改旧接口语义。
- [x] 保存方案采用 `WHERE id=? AND user_id=? AND version=?` 条件更新，成功时 version+1；影响 0 行则返回冲突提示，不能静默覆盖。
- [x] 验证 Flyway 迁移与回归测试，使用真实数据库复查导入、保存、重新读取。

**完成标志：** 刷新后能取回相同的字幕版本和选段方案，跨账号访问失败。

## Task 4: 文本回看、删除恢复与连续预览

**Files:** 新建 `TextVideoEditor.vue`、`useTextVideoEditor.js`、`editorApi.js`、`textVideoEditor.css`、`useTextVideoEditor.test.js`；小范围修改 `App.vue`。

**Interfaces:** editorApi 复用 apiRequest；组件接收 `mediaId`，通过 `close` 事件退出；composable 负责加载、保存和播放器生命周期。使用现有 `/media/playback?id=` 获取签名播放地址。

- [x] 先写 composable 测试，验证关闭后迟到请求不能覆盖重开的另一个视频、删除可恢复、空选段不能预览/导出、保存冲突保留当前编辑内容。
- [x] 运行 `node --test src/useTextVideoEditor.test.js src/clipRanges.test.js`，确认失败后实现。
- [x] 从原资料条目增加“文本剪辑”入口；保持提取文字/Video Agent 入口和处理函数。组件独立管理状态，不把编辑器逻辑塞入 useAnalysisWorkspace。
- [x] 界面提供 SRT/VTT 选择、自动转写入口、时间标记、勾选保留、删除/恢复、全选、所选总时长、保存、连续预览和导出。
- [x] 通过 video 的 loadedmetadata/timeupdate/seeking/ended 更新当前 cue；预览只读取已规范化区间，到区间结束时跳转下一段，末段停止。提示这种预览不是最终无缝成片。
- [x] 自动高亮允许手动滚动阅读，不在每次 timeupdate 强制抢滚动；暂停不丢选择，关闭释放定时器、事件和 Blob URL。
- [x] 复查桌面和手机，键盘可操作，状态不只靠颜色。没有时间轴时明确提示导入字幕/生成时间轴，不能展示假精细时间。

**完成标志：** 原视频上能点击定位、播放高亮、删中间一段、恢复、按选段预览与保存恢复。

## Task 5: 真实时间戳 ASR

**Files:** 新建 `TimedAsrProvider.java`、`HttpTimedAsrProvider.java`、`TimedTranscriptionService.java` 及测试；扩展 `EditorController.java`；添加独立配置到 `application.properties` 和 `.env.example`，不修改原 `AliyunAsrUtils.audioToText` 合约。

**Interfaces:**

```java
public interface TimedAsrProvider {
    List<EditorModels.TimedCue> transcribe(java.nio.file.Path audioFile);
}
```

配置为 `editor.timed-asr.url`、`editor.timed-asr.api-key`、`editor.timed-asr.model`，使用对应 `EDITOR_TIMED_ASR_*` 环境变量，不填写真实 Key。

- [x] 先写 HTTP 适配测试：响应 `segments` 中的 start/end 秒数转毫秒；缺少 segments、时间倒序、服务失败与仅返回 text 不得被当作精细转写成功。

```json
{"segments":[{"start":0.12,"end":2.34,"text":"测试语音"}]}
```

该 JSON 仅作为解析器测试夹具，期望 startMs=120、endMs=2340；不作为 README 中的真实 ASR 结果。

- [ ] 验证现有服务是否能真实提供该类时间戳，依据实际官方能力和响应选择请求参数；如需另一种响应格式，在这一接口内适配，不假设所有提供商相同。
- [x] 使用有界异步任务将文档状态写入 MySQL，保存真实 cue，并提供 transcribe/status 接口；复用已有媒体工具提取音频，保留 source=ASR_TIMED。
- [x] 配置不足时返回“时间轴转写未配置，可导入字幕”，保持原纯文本转写仍可用。若需要用户配置服务 Key，集中说明所需字段，不索取校园凭证。
- [ ] 用可处理的短口播或明确标为合成的测试语音真实调用一次，人工核对至少 3 个切点，记录偏差及手动调整需要。

**完成标志：** 有一份通过真实服务生成、可播放核对的时间轴。Key/服务不可用时保持本任务未完成，不阻塞其他任务的开发，也不把字幕导入算作本任务完成。

## Task 6: 指定时间截图与实际裁剪

**Files:** 新建 `FfmpegMediaTools.java`、`FfmpegMediaExporter.java`、`FfmpegMediaExporterTest.java`；扩展 `EditorController` 的 `/frame`；前端接入帧预览。

**Interfaces:**

```java
// FfmpegMediaTools
long probeDurationMs(java.nio.file.Path source);
byte[] extractFrame(java.nio.file.Path source, long atMs);
// FfmpegMediaExporter
void export(java.nio.file.Path source,
            List<EditorModels.ClipRange> ranges,
            java.nio.file.Path output);
```

- [x] 使用 FFmpeg 生成 6 秒可复现红/绿/蓝测试素材，每段 2 秒，并保留一份无音轨和一份带音轨样例。素材来源与构造方式写入验证记录。
- [x] 先写实际媒体测试：只保留 [0,2000) 与 [4000,6000)，输出时长约 4 秒、顺序红→蓝、不包含中段绿色；预期允许时长误差 200ms。
- [x] 运行媒体测试确认在 exporter 未实现时失败；工具缺失时明确报告阻塞，不用全跳过测试宣称通过。
- [x] ProcessBuilder 使用固定命令参数数组；探测真实流信息，视频 trim/setpts、音频 atrim/asetpts、concat 后统一 H.264/AAC；无音轨显式走无音轨分支。
- [x] 截图接口校验所有权和时间，返回真实 JPEG；前端通过鉴权 fetch 生成 Blob URL，明确只是“该时间的画面”，不声称是完整或最清晰板书。
- [x] 为子进程设置超时和日志上限；失败销毁子进程、删除本任务不完整输出。清理前检查目录位于 editor 任务根目录。
- [ ] 真实播放输出，试听多段边界与音画同步；在验证记录中保留输入区间、期望/实际时长和检查结果。

**完成标志：** 文件内容真的发生相应删减，截图也确实来自对应视频，而非只改变页面状态。

## Task 7: 异步导出、重复提交与下载

**Files:** 新建 `ExportService.java`、`ExportExecutorConfig.java` 与 `ExportServiceTest.java`；扩展 `EditorStore/Mapper/Controller` 和前端导出状态。

**Interfaces:** POST exports 返回 jobId/state；GET status 返回持久化状态；下载接口完成后签发 MinIO 地址。requestHash 使用规范化的用户、源素材、字幕版本、区间和 preset，不包含临时签名 URL。

- [x] 先写测试：相同指纹复用任务、不同用户不共用、导出使用提交时的快照、其他用户不能读任务/下载、队列满标失败、重试次数和状态转换正确。
- [x] 运行 `./mvnw.cmd -Dtest=ExportServiceTest test`，确认缺少行为导致失败。
- [x] 配置专用 worker=1、queueCapacity=4、有界拒绝策略；在数据库事务提交后提交执行器，禁止阻塞 HTTP 请求直至转码完成。
- [x] 使用唯一约束与条件状态转换；派发拒绝和超时写 FAILED，错误摘要不包含 Key、签名地址或本地敏感路径。
- [x] 本地源文件从已有 MinIO 读取接口复制到独立任务目录，调用已通过测试的 exporter，验证输出后上传；库中保留稳定对象标识，下载时签名。
- [x] 单实例启动时将残留 QUEUED/PROCESSING 标为中断失败；用户重试时重新排队，不声称支持进程内转码断点恢复。
- [x] 前端每约 2 秒查询当前任务；切换/退出取消轮询。重新打开可以查询最近任务；方案改动后不将旧版本结果冒充最新方案。
- [ ] 真实走通点击导出→排队/处理中→完成→下载→播放器核对，并测试一次明确失败。

**完成标志：** 可得到真实 MP4，重复提交不启动相同并行转码，失败可理解、可重试。

## Task 8: 总体验收与面试材料

**Files:** 更新 README、PRODUCT.md、`docs/text-video-editor-validation.md`，新截图放 `docs/images/text-editor-*.jpg`；校园工作继续仅保留 TODO.md。

- [x] 运行原有与新增前端测试、生产构建、后端完整测试；只有与本功能相关的失败才修复，已有无关问题单独记录。
- [x] 用同一份允许处理的真实视频完成上传→时间轴→选段→回看→截图→保存恢复→导出→下载播放。
- [ ] 使用第二账号检查字幕、方案、截图、任务与下载归属；检查空选、边界区间、字幕替换、旧版本、任务失败与播放签名过期。
- [x] 浏览器检查桌面与手机，录下实际支持的操作截图。将手工字幕、真实 ASR 与固定 demo 明确区分。
- [x] 写清个人新增模块、复用原有部分、实际遇到的问题和选择依据；所有效果数字来自真实测量，不生成虚假用户、Star、性能或平台接入经历。
- [x] 做一次聚焦的只读代码审查，处理阻塞问题，核对差异与状态；保留未提交工作供用户验收。

**最终演示：** 用户上传一段视频，得到或导入真实时间轴，点击文字回看、删除中间一句、预览剩余片段、保存恢复并导出正确的视频；同济 Look 关联仍显示为后续轻量扩展。

## 实施状态（2026-10-19）

首版字幕导入→文字定位→选段→保存恢复→截图→真实 MP4 导出下载播放已通过本地联调。前端 55 项测试与构建通过，后端 112 项测试通过。最终运行版本真实 HTTP 验收 23 项通过（已有素材未重复上传）；此前包含上传的一轮为 24 项。桌面和 390px 手机页面已检查。

未完成项保留：真实 ASR 服务配置与精度验收；人工试听判断口播切点的主观自然程度；真实导出故障/过期签名的浏览器完整流程。对应异常已进行自动化测试，但不等同于上述人工验收。浏览器扩展禁止访问文件网址，未完成文件选择器上传的端到端验收；服务端真实分片上传与字幕导入已通过。没有提交、推送或接入同济平台。

# DoVideo-AI

<!-- impeccable:product-schema 1 -->

## Platform

web

## Product Purpose

视频内容处理工作台：导入视频，查看处理状态，提取文字，围绕分析目标生成带时间戳证据的结构化内容。本轮新增目标是用带时间轴文本回看、选择、预览及导出原视频片段。

## Capabilities and Constraints

- 现有 Vue 3、Vite、Marked 前端；没有独立路由，登录、转写、AI 分析使用弹层。
- 保留原有 API、鉴权、上传、SSE、任务恢复、检索和分析流程；上轮视觉改版已完成。
- 本轮按 docs/superpowers/specs/2026-10-19-text-video-editor-design.md 实施独立文本剪辑模块，新增必要后端接口；单视频、句段级选择、保持原顺序，不做多轨或字级剪辑。
- 优先沿用现有依赖，不自动提交、推送或部署。
- `?demo` 是项目自带的固定示例，不代表本次真实视频处理或模型调用。
- 本地后端、真实视频处理与模型结果需单独验证，见 docs/text-video-editor-validation.md；未验收项不描述为已完成。
- 同济 Look 仅作为后续轻量来源关联场景，见 TODO.md；本轮不实现校园认证或受保护视频抓取。

## Brand Commitments

名称为 DoVideo-AI。专业、简洁的工作台；浅灰背景、白色内容面板、深色视频预览、克制的蓝紫色操作强调。

## Evidence on Hand

原始代码、现有测试和 README；演示数据来自 `client/src/demoData.js`。功能截图必须从本地运行页面获取并标明演示与真实结果的区别。

## Product Principles

- 原有功能和业务流程优先。
- 状态、字幕、视频和 AI 内容有清晰的信息层级。
- 截图、功能说明与验证结论一致。
- 保留 MIT 版权许可与必要来源说明。

# 百炼句段转写配置

此适配使用北京地域的 `paraformer-realtime-v2`，从后台发送本地音频，返回服务提供的句段文字及毫秒时间。无需将本机 MinIO 暴露到公网，也不需要视频自带字幕。

## 本地配置

在被 Git 忽略的 `.env` 中配置：

```dotenv
EDITOR_TIMED_ASR_PROVIDER=bailian
DASHSCOPE_API_KEY=在本机填入自己的密钥
BAILIAN_ASR_MODEL=paraformer-realtime-v2
BAILIAN_ASR_URL=wss://dashscope.aliyuncs.com/api-ws/v1/inference
```

密钥需要有对应语音模型的权限。不要将 `.env` 提交到 Git；`.env.example` 只保留空值。修改后重启后端，前端不读取密钥。百炼推荐的业务空间专属域名也可以通过 URL 配置，地域和凭据必须匹配。

额度与费用以账户控制台为准。先使用短测试素材验证；本仓库不自动充值、开通付费套餐或变更云端权限。

## 使用流程

上传视频后进入文本编辑器，后台处理音频并生成时间轴。只有服务确认结束、句段通过验证后才展示为完成；中间识别结果不作为可剪辑文字。用户可关闭编辑器，后台继续处理，再从资料库打开查看。

文字识别有错字或断句误差时，请回看核对。当前编辑为整句段保留/删除，不能通过改写文字生成新台词。无语音视频不能仅靠 ASR 产生可用时间轴，仍可导入匹配的手工字幕。

一份已完成自动转写可供原文字面板和视频分析使用；总结默认理解原视频完整内容，剪辑方案控制导出区间。AI 总结所需的文本大模型 Key 是另外一项配置，**仅授权语音模型的百炼 Key 不代表能调用文本模型**。

保留原 Whisper 兼容适配：使用 `EDITOR_TIMED_ASR_PROVIDER=whisper` 并填写相应的 `EDITOR_TIMED_ASR_URL/API_KEY/MODEL`。不要把百炼 Key 误填到 Whisper 接口配置中。

## 可复现素材与证据

`docs/examples/create-text-editor-demo.ps1` 可生成原创合成课程口播。测试自动转写时只上传 MP4，不能先导入生成脚本配套的 SRT。手工字幕、真实 ASR、界面固定 demo 必须分别注明。

已于 2026-10-20 真实调用百炼处理 37.320 秒合成口播并获得 8 个最终句段。这证明该账户与接口可用，不代表真人口音、长视频或剪辑切点已全面验收。完整链路结果见 [验证记录](text-video-editor-validation.md)，实现中的取舍见 [决策与面试记录](development/text-video-editor-decisions.md)。

## 协议依据

- [百炼客户端事件](https://help.aliyun.com/zh/model-studio/paraformer-client-events)
- [百炼服务端事件](https://help.aliyun.com/zh/model-studio/paraformer-server-events)
- [Java 调用与音频帧要求](https://help.aliyun.com/zh/model-studio/paraformer-real-time-speech-recognition-java-sdk)

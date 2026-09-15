<!-- Copyright © 2026 立方田 &lt;managecode@gmail.com&gt; -->
# ADR-0003：语音识别 Provider 化

- 状态：已接受
- 日期：2026-09-02

## 决策

语音采集、VAD、识别、标点和文本提交解耦。ASR 通过 `SpeechProvider` 契约提供临时和最终假设；运行时可按平台能力、隐私策略和资源预算选择本地、系统或云端 Provider。

## 后果

即时听写与长音频转录共享 Provider 和模型管理，但使用独立会话与资源策略。iOS 键盘扩展不直接采集麦克风。

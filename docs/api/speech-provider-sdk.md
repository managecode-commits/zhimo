# Speech Provider SDK v1

`ime_speech::SpeechProvider` 把采集、识别和输入提交解耦。Provider 接收规范化 PCM frame，
产生 Partial、Final、Error 和 Ended 事件；不得直接触碰候选 UI、文本框或用户数据库。

实现必须满足：

- `start` 校验语言、离线策略和模型状态，并返回隔离会话句柄。
- `push_audio` 拒绝零采样率、零声道、非有限样本和跨声道不完整 frame。
- `poll` 不阻塞输入线程；重型推理放到 worker。
- `finish` 触发端点识别，`cancel` 删除缓冲并使句柄失效。
- 只将用户确认词库映射为本地热词；原始音频默认不持久化。
- 密码 scope、禁麦克风策略或 offline-required 与 Provider 不匹配时必须在访问设备前拒绝。

仓库内 Mock Provider 用于契约测试，whisper.cpp Provider 已完成中英文真实音频验证，Android
和 Apple 容器壳展示系统 Provider 的事件注入。新增 sherpa-onnx、企业内网或云端 Provider
都应通过相同契约，并另行声明联网、成本、数据保留和取消语义。

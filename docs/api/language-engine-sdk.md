<!-- Copyright © 2026 立方田 &lt;managecode@gmail.com&gt; -->
# Language Engine SDK v1

新语种通过实现 `ime_core::InputEngine` 接入，不得把语种概念加入平台桥。最小实现需要提供
稳定 engine ID、会话状态、`process` 动作和可选反馈处理；输入、候选、组合和提交全部使用
`ime-core` 的通用类型。

## 接入流程

1. 新建独立 crate，实现 `metadata`、`create_session`、`process`、`apply_feedback` 和
   `flush`。
2. 对字符、退格、提交、取消、候选选择、密码 scope 和异常输入建立确定性事件回放测试。
3. 如需个人化，只输出逻辑学习记录；不要让平台层或语言包直接写数据库。
4. 创建符合 `schemas/language-pack.schema.json` 的 manifest，列出语言、引擎、最低运行时、
   资源和许可证。
5. 由 `ime-package` 生成资源哈希并用发布密钥签名；运行时拒绝未签名、哈希不符或路径穿越包。
6. 注册到 `LanguageRouter`，验证与已有 Latin/Pinyin 引擎切换时组合态不会串会话。

`ime-engine-vietnamese` 是第二个转换型参考实现，使用同一契约完成 Telex
`tieengs → tiếng`，用于证明底座不是拼音专用。生产引擎仍需补齐完整语言规则、词典资源、
许可证和母语用户质量测试。

兼容规则：同一 ABI 主版本内只能增加带 capability 的可选能力；持久化 schema 和语言包
schema 独立版本化。引擎崩溃或拒绝事件时，平台必须允许关闭组合并回退原始文本。

<!-- Copyright © 2026 立方田 &lt;managecode@gmail.com&gt; -->
# Platform Bridge v1

平台桥只负责操作系统事件与 Runtime C ABI 之间的转换，不包含语言、排序或学习逻辑。

## 生命周期

1. 平台输入法进程加载并创建 Runtime。
2. 文本框获得焦点时按选定 profile 创建 Session。
3. 键盘、触摸或允许的语音事件被规范化后发送给 Session。
4. 平台依次应用返回的 Action；提交动作必须保持原顺序。
5. 焦点丢失时按平台语义提交、取消或暂存 Composition，然后关闭 Session。
6. 进程退出前释放 Runtime；后台同步服务的存活不得成为前置条件。

## 必须声明的能力

- marked/composition text
- surrounding text 读取范围
- replace/delete surrounding text
- secure field detection
- application identity
- hardware keyboard
- microphone
- shared storage
- background service
- network

Runtime 只能使用平台已声明且当前会话允许的能力。安全文本框强制覆盖所有普通配置：禁学习、禁上下文、禁网络、禁剪贴板。

## C ABI 版本与动作读取

平台加载后先调用 `ime_runtime_abi_version()` 和 `ime_runtime_capabilities()`。当前 ABI 为
`1.1`（返回值 `0x00010001`）；主版本不兼容时必须停止加载，次版本不足时按 capability
降级。能力位定义以 `include/zhimo_ime.h` 为唯一来源。

动作有两种兼容读取方式：

- `ime_runtime_last_actions_json()` 保留给现有 Kotlin、Swift 和 Python 桥。
- `ime_runtime_action_*`/`ime_runtime_candidate_*` 提供无 JSON 依赖的结构化查询，供
  Fcitx、TSF 等原生壳使用。

结构化查询返回的字符串由 Runtime 持有，只在下一次查询或会话变更前有效；平台必须立即
复制。动作类型 `1..5` 依次为组合更新、候选、提交、关闭和忽略。候选字段 `0..3` 依次为
ID、显示文本、提交文本和注释。平台必须按动作原顺序应用，不能把 Commit 合并或重排。

可选动作 `6` 为候选分页，`7` 为 `PinyinReadings { readings, selected }` 读音筛选状态，
详情从 JSON 接口读取。九键可通过候选选择接口发送 `pinyin-reading:<reading>`，
仅筛选候选、不提交文字；空 reading 恢复自动。旧平台应忽略未知动作编号。

语音既可由平台系统 ASR 通过 `ime_runtime_speech_result()` 注入，也可使用
`ime_speech_*` 管理离线 whisper.cpp 会话。密码 scope 下启动和结果注入都会被拒绝；取消
页面或丢失焦点时必须调用 `ime_speech_cancel()`。

## 错误与降级

- Runtime 不可用：透传可打印字符。
- 引擎失败或超时：取消组合并允许用户提交原始输入。
- 后台服务不可用：保持本地基础输入，不循环重连阻塞宿主。
- 无上下文能力：关闭依赖前文的预测，不猜测上下文。
- 无麦克风能力：隐藏自有录音入口或显示平台认可的替代入口。

## 线程约束

平台 UI/按键线程只允许调用有明确低延迟保证的内存路径。磁盘整理、同步、模型加载、网络和 ASR 推理必须通过异步队列或后台服务执行。

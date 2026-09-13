# Android IME

全平台统一入口和 Debug/Release 产物索引参见
[全平台构建、安装与验收手册](../../docs/构建与验收.md#3-android-输入法-app)。
本轮拼音根因、按键矩阵、验收证据和剩余质量边界见
[拼音输入与 Android 键盘评估](../../docs/拼音输入与Android键盘评估.md)。

这是可安装的 `InputMethodService` 工程骨架，包含系统声明、安装/权限引导、触摸键盘、候选栏、密码框策略、JNI/C ABI 桥和 Android 系统/端侧 SpeechRecognizer Provider。语音结果也通过统一 Runtime 的 Partial/Final 事件提交。

构建前先为 Android 目标编译 `ime-ffi`，并将各 ABI 的 `libime_ffi.so` 放入
`app/src/main/jniLibs/<abi>/`。仓库已固定 Gradle Wrapper 9.5；使用 JDK 17、Android SDK
Platform 37.0、NDK 28.2.13676358、`cargo-ndk 4.1.2` 与 AGP 9.3.2 执行：

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk --locked --version 4.1.2
```

脚本会自动识别标准 `~/.cargo/bin`；若需手工激活当前终端，执行
`source "$HOME/.cargo/env"`。

之后在仓库根目录使用统一入口：

```bash
./tools/verify-android-beta.sh
```

默认 `tools/build-android-core.sh` 构建参考拼音版本。生产型构建将
`ANDROID_RIME_ROOT` 指向包含 `arm64-v8a`、`armeabi-v7a`、`x86_64` 子前缀的目录；每个
前缀需提供 `include/rime_api.h`、`lib/librime.so` 及它的动态依赖。脚本会启用
`native-librime` 并复制各 ABI 依赖。APK 首次启动原子安装自带的 Apache-2.0 全拼 schema，
若 librime 初始化或语言包不可用则自动降级到参考拼音。

仓库提供完整的免 sudo 自动化入口。它会下载锁定版本的官方 librime 源码和
fcitx5-android 预构建依赖，使用现有 Android SDK/NDK 为三个 ABI 编译、裁剪并校验
`librime.so`，随后构建 APK，并在已连接设备上继续运行 instrumentation 测试：

```bash
./tools/verify-android-rime-beta.sh
```

默认 Rime 前缀输出到 `target/android-rime`，下载和中间编译文件缓存在
`${XDG_CACHE_HOME:-$HOME/.cache}/shurufa-librime`。再次执行会复用源码、依赖和编译缓存。
可通过 `ANDROID_RIME_ROOT` 改变前缀目录，通过 `SHURUFA_ANDROID_RIME_CACHE` 改变缓存目录。
只构建 Rime 前缀而不打 APK 时执行 `./tools/build-android-librime.sh [输出目录]`。
独立产物为 `app/build/outputs/apk/debug/app-debug-rime.apk` 及同目录 SHA-256 文件；Rime
模式的设备测试会额外强制断言原生引擎初始化并成功选中，不能通过参考拼音降级掩盖失败。

内置 `pinyin_simp` 来自官方 `rime/rime-pinyin-simp` 的锁定提交，包含 65,125 条词典记录；
完整上游 Apache-2.0 许可文本随 APK 打包。它解决了原 Beta 约百条验证词典导致普通拼音无
汉字候选的问题，但仍是基础词典，不代表已经完成整句模型、模糊音和行业词库质量验收。
运行 `./tools/update-rime-pinyin-simp.sh` 可按固定提交和 SHA-256 重建 Android 资源及9键
回退数据，哈希不一致时脚本会立即失败。

完整验证入口不会自动接受任何许可证：

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
# 可选：export ANDROID_RIME_ROOT=/path/to/per-abi-librime-prefixes
./tools/verify-android-beta.sh
```

上述环境变量可显式指定，也可省略：脚本会依次从 `ANDROID_SDK_ROOT`、Android
Studio `local.properties`、标准用户安装目录和当前临时工具链中发现有效 SDK，
并自动推导 NDK 与 JDK。候选目录必须同时包含许可证记录和所需 SDK Platform，
空目录不会被误判为 SDK。自动发现只读取现有许可证记录，不会代替用户接受许可证。

验证通过后的可安装 Debug APK 位于
`platform/android-ime/app/build/outputs/apk/debug/app-debug.apk`；脚本同时编译 AndroidTest
APK，并阻断 JNI `DT_NEEDED` 中的构建机绝对路径。

连接设备时脚本会安装 APK 并运行六项 instrumentation 测试：中文候选/提交、Rime
资源损坏恢复、密码语音隔离、端侧优先和联网显式授权策略、完整词典词汇/非法后缀回归，
以及9键常用词提交。Rime 强制模式还会要求 `putao` 返回“葡萄”，避免小验证词典冒充
完整资源。手工启用组件可执行：

```bash
adb shell ime enable dev.shurufa.ime/.ShurufaInputMethodService
adb shell ime set dev.shurufa.ime/.ShurufaInputMethodService
```

候选 JSON 的字段约定来自 `docs/api/platform-bridge.md`。配套 Activity 已提供启用/选择输入法、
录音授权、默认中英文引擎和本地学习开关；IME 将 package ID、密码/邮箱/URL scope 与离线网络
策略传入核心；`IME_FLAG_NO_PERSONALIZED_LEARNING` 也会覆盖用户设置。无端侧系统识别器时，
必须由用户明确开启联网语音。发布前仍需真机完成输入连接、横竖屏、后台回收、无障碍、耗电、录音权限和
Play 签名测试。

中文模式的功能栏可在“26键”和“9键”间切换，选择会保存在本机；英文模式始终使用
26键，再切回中文时恢复用户上次选择。9键采用标准手机键位映射（ABC=2、DEF=3、…、
WXYZ=9），例如 `64426` 可得到“你好”。当前 Beta 的数字拼音匹配使用内置参考词库；
它现在与26键使用同一份 65,125 条上游词典数据，并叠加项目自有的常用词优先级。

参考拼音回退包含 65,125 条上游词典记录和187条项目常用词覆盖，支持连续拼音、带撇号音节分隔、
9键数字签名和精确输入习惯重排；非法后缀不会再错误匹配较短拼音。它仍是确定性离线
回退，而不是生产级整句语言模型。

主键盘的“123”按钮进入字面量数字页，支持 `0-9`、句点、逗号、问号、感叹号、
`@` 和 `#`；“符号”按钮进入常用符号页，可在中文标点与英文/编程符号间切换。
数字、电话和日期时间输入框默认打开数字页。数字页中的按键直接提交字符，不会被9键
拼音引擎解释；数字/符号页的空格、删除和回车也直接操作输入框，避免残留拼音组合态
提交错误候选。切换到数字、符号或另一语言布局时会先取消未完成的拼音预编辑，不会把原始
拼音意外提交到输入框；“ABC”可回到之前的中文或英文键盘布局。当前键盘页和中英文符号
状态在同一编辑器横竖屏重建时保持不变，切换到不同输入框时再按其输入类型初始化。

键盘提供独立模式工具栏、英文大小写、中英文逗号/句号、拼音分隔符、随编辑器变化的
搜索/发送/完成/下一项/前往键、深色配色和触觉反馈。候选栏显示拼音注释、高亮首选并
支持本地翻页；语言按钮明确显示“切英/切中”，存在拼音组合时空格键显示“选词”，提交后
恢复“空格”，避免把选词键误认为字面空格。选词、空格或回车提交组合后立即刷新SQLite学习数据。

UI v2 进一步提供候选展开面板、9键可读拼音预编辑、3×4上下文数字键盘、中英文双页符号、
emoji、用户显式触发的剪贴板粘贴、长按连续删除、字母长按副键、按键预览、语音状态及错误
反馈、左/右手布局、三档键盘高度、高对比度和可关闭触觉反馈。设置页可滚动；密码框禁止
语音、学习和剪贴板读取。滑行输入仍需独立轨迹解码器，未用普通按键事件伪装实现。

已验证的自动化设备基线是 Android 15 / API 35 AOSP ATD x86_64；
`shurufa_visual_api35` 模拟器上六项 instrumentation 测试全部通过，且已可视化验证26键
`putao → 葡萄`、空格选词提交，以及9键 `78826 → 葡萄`。模拟器验收不替代上述真机矩阵。

Release 签名信息仅从进程环境读取，不得将 keystore 或口令写入仓库：

```bash
export SHURUFA_ANDROID_KEYSTORE=/secure/path/release.jks
export SHURUFA_ANDROID_KEY_ALIAS=release
export SHURUFA_ANDROID_STORE_PASSWORD='...'
export SHURUFA_ANDROID_KEY_PASSWORD='...'
export SHURUFA_ANDROID_CERT_SHA256='expected signing certificate SHA-256'
./tools/verify-android-release.sh
```

脚本会验证 APK 签名证书与登记的 SHA-256 指纹一致、三种 ABI 的 JNI 库、生产构建中的 `librime.so`，并生成
`app-release.apk.sha256`。口令应由本地机密管理器或 CI secret 在进程启动时注入。

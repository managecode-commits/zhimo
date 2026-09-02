# Android IME

这是可安装的 `InputMethodService` 工程骨架，包含系统声明、安装/权限引导、触摸键盘、候选栏、密码框策略、JNI/C ABI 桥和 Android 系统/端侧 SpeechRecognizer Provider。语音结果也通过统一 Runtime 的 Partial/Final 事件提交。

构建前先为 Android 目标编译 `ime-ffi`，并将各 ABI 的 `libime_ffi.so` 放入
`app/src/main/jniLibs/<abi>/`。仓库已固定 Gradle Wrapper 9.5；使用 JDK 17、Android SDK
37、NDK 28.2.13676358 与 AGP 9.3.2 执行：

```bash
./gradlew :app:assembleDebug
```

默认 `tools/build-android-core.sh` 构建参考拼音版本。生产型构建将
`ANDROID_RIME_ROOT` 指向包含 `arm64-v8a`、`armeabi-v7a`、`x86_64` 子前缀的目录；每个
前缀需提供 `include/rime_api.h`、`lib/librime.so` 及它的动态依赖。脚本会启用
`native-librime` 并复制各 ABI 依赖。APK 首次启动原子安装自带的 Apache-2.0 Beta schema，
若 librime 初始化或语言包不可用则自动降级到参考拼音。

内置词库只用于安装闭环和真机冒烟测试，不代表生产中文质量；正式发行仍须替换为完成许可
审查、签名和质量验收的语言包。

完整验证入口不会自动接受任何许可证：

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
# 可选：export ANDROID_RIME_ROOT=/path/to/per-abi-librime-prefixes
./tools/verify-android-beta.sh
```

连接设备时脚本会安装 APK 并运行三项 instrumentation 测试：中文候选/提交、密码语音隔离，
以及端侧优先和联网显式授权策略。手工启用组件可执行：

```bash
adb shell ime enable dev.shurufa.ime/.ShurufaInputMethodService
adb shell ime set dev.shurufa.ime/.ShurufaInputMethodService
```

候选 JSON 的字段约定来自 `docs/api/platform-bridge.md`。配套 Activity 已提供启用/选择输入法、
录音授权、默认中英文引擎和本地学习开关；IME 将 package ID、密码/邮箱/URL scope 与离线网络
策略传入核心；`IME_FLAG_NO_PERSONALIZED_LEARNING` 也会覆盖用户设置。无端侧系统识别器时，
必须由用户明确开启联网语音。发布前仍需真机完成输入连接、横竖屏、后台回收、无障碍、耗电、录音权限和
Play 签名测试。

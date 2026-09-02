# Android IME

这是可安装的 `InputMethodService` 工程骨架，包含系统声明、安装/权限引导、触摸键盘、候选栏、密码框策略、JNI/C ABI 桥和 Android 系统/端侧 SpeechRecognizer Provider。语音结果也通过统一 Runtime 的 Partial/Final 事件提交。

构建前先为 Android 目标编译 `ime-ffi`，并将各 ABI 的 `libime_ffi.so` 放入
`app/src/main/jniLibs/<abi>/`。然后用 JDK 17、Android SDK 37、NDK 28.2.13676358、Gradle 9.5 与 AGP 9.3.2 执行（首次可由 Android Studio 生成 Gradle Wrapper）：

```bash
./gradlew :app:assembleDebug
```

候选 JSON 的字段约定来自 `docs/api/platform-bridge.md`。配套 Activity 已提供启用/选择输入法、
录音授权、默认中英文引擎和本地学习开关；IME 将 package ID、密码/邮箱/URL scope 与离线网络
策略传入核心。发布前仍需真机完成输入连接、横竖屏、后台回收、无障碍、耗电、录音权限和
Play 签名测试。

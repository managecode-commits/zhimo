# Zhimo v0.1.1-preview.20260921.1 构建与验证摘要

Copyright © 2026 立方田 <managecode@gmail.com>

构建日期：2026-09-21。所有附件为开发预览，不是稳定正式版。

## 源码与产物

| 产物 | 构建源码 | 配置 |
|---|---|---|
| Android APK | `0d260c9792e2d3fe976c25715b9d23fc84974a49` | versionCode 4；三 ABI；debug 签名；Rime、手写和 Whisper；实验流式开关关闭 |
| Ubuntu 24.04 / Debian 12 | `0d260c9792e2d3fe976c25715b9d23fc84974a49` | amd64，分别匹配 glibc 2.39 / 2.36；native-librime、共享语音、IBus / Fcitx5 |
| Windows | `c0ef55055dfe0996670db31b5caccbff757db5c3` | MinGW POSIX release；x64 系统，x64/x86 TSF；独立手写/语音面板为 x64；未签名 |
| macOS | `40b95fc2613162e55f9bfd25570c6426c9efb159` | Universal arm64/x86_64；最低 macOS 12.0；ad-hoc 签名，未公证 |

APK/Linux 构建后新增的修改仅涉及 Windows/macOS、词库测试换行兼容及发布文档；不改变本次 APK/Linux 的产品源码。各产物保留实际构建提交，不将其伪写成最终文档提交。

Windows 打包器接收预构建二进制，因此其包内信息仍保守标记 `binary_source_verified: false`、`source_head: unverified-prebuilt-inputs`。本轮确实运行了完整双架构构建脚本；打包时发布说明正在编辑，故 `source_dirty: true`。这些记录及 SHA256 校验和均不是发布者签名或可复现构建证明。

## 通过的检查

- 本地 `tools/verify.sh`：Rust 格式、Clippy、工作区测试、词库同步、发行契约、IBus staging、C ABI 及便携 TSF 核心探针。
- Android：Gradle 构建、20 项 JVM 测试、Lint；APK 签名、三 ABI ELF、Rime 依赖与内置语音模型哈希检查。模拟器 API 35：2 项连拼测试、3 项快速输入有序性测试通过；按键交互 1 项重跑通过。
- Linux：两个目标容器各完成核心/Fcitx5/共享语音构建、33 项 Python 按键/桌面测试、GUI smoke、Rime 回归；另在匹配发行版的验证容器安装三个 DEB，验证 Fcitx5 依赖加载、已安装 Rime、7 项大小写测试，再卸载。
- [跨平台 CI 35621313716](https://github.com/managecode-commits/zhimo/actions/runs/35621313716)：所有任务通过。Windows 的安装脚本解析/模拟注册、MSVC 编译及原生 TSF 探针通过；并非对发行 MinGW ZIP 的实机安装验收。
- [macOS Actions 35621183837](https://github.com/managecode-commits/zhimo/actions/runs/35621183837)：双架构编译、原生 Swift/C ABI 全拼/简拼选词、UTF-16 检查、包内动态库路径检查及签名验证通过。

## 保留的失败记录与限制

- Android 模拟器冷启动出现系统 UI ANR 弹窗，关闭后继续；第一次按键 UI 测试在初始工具栏可见性断言失败，未改产品代码的重跑通过。真机首次唤起与冷启动时序仍需检查。
- 旧 Linux 构建镜像缺少 `pulseaudio-utils`，首次 `dpkg -i` 依赖检查失败；切换到已安装该依赖的发行版验证镜像后安装/卸载通过。宿主机没有安装这些 DEB。用户应使用 APT 安装所选插件和核心，自动解析依赖。
- macOS 前三次构建暴露安全输入 API、NSWindowController 属性命名和候选动作协议问题，修正后第四次通过。Windows 的 MSVC 导出、宏、库/GUID 与 CRLF 测试问题也已修正并经原生 CI 验证。
- Windows/EmEditor、macOS IMK、Linux X11/Wayland 和手机麦克风、识别准确率、冷启动及尾延迟仍需真实设备验收。
- Linux 字形融合实验尚未接入；流式语音实验代码已提交，但本版 APK 不包含实验流式模型或运行库。未提供 iOS / HarmonyOS 安装包。

下载后用附件 `SHA256SUMS` 核验文件；不要将校验和等同于可信发布者签名。

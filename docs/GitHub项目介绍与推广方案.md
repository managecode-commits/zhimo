# GitHub 项目介绍与预览推广

Copyright © 2026 立方田 <managecode@gmail.com>

## 对外定位

推荐短描述：`开源、离线优先的输入法：拼音、手写与语音转文字。Android / Windows / Linux 开发预览，Rust 通用底座。`

中文介绍：知墨 Zhimo 是一个开源、离线优先的输入法项目，将拼音、手写与语音转文字连接到可扩展的 Rust 底座。当前邀请 Android、Windows、Ubuntu 和 Debian 用户参与预览测试。

推荐 Topics：`input-method`、`ime`、`pinyin`、`handwriting-recognition`、`speech-to-text`、`offline`、`rust`、`windows`、`linux`、`android`。不要使用与现有交付无关的热词，也不把实验性 macOS 标成稳定支持。

## 首页与发布页

- README 首屏：一句话价值、真实平台状态、版本／安装／反馈链接。
- Releases：标记 Pre-release，明确安装方式、未签名状态、验证边界、模型许可证和校验值。
- 演示素材：只使用 Zhimo 自身运行界面；分别演示 nihao/nh 上屏、单字手写、离线语音确认。不要拿参考输入法截图当成 Zhimo 成果，不包含私人聊天和账号。
- 仓库已配置介绍侧栏与 Topics；简介应与安装包的实际发布状态保持一致。

## 推广顺序

1. 先邀请少量真实用户安装预览版，集中收集状态栏、Wayland 和手写误识别反馈。
2. 形成明确的安装记录与已知问题，再由项目作者发到适合的中文开源、Rust、Linux 和输入法社区，遵守各社区宣传规则。
3. 每次更新围绕可核验变化发布简短演示，不以 Star 数量作为唯一指标；优先观察安装成功、问题复现和修复闭环。

示例发布文案（待预览版实际发布后使用）：

> 我在开发开源输入法「知墨 Zhimo」：支持拼音、离线手写和录音后本地转文字，底层使用 Rust。现邀请 Android、Windows、Ubuntu 24.04／Debian 12 用户测试开发预览版。项目还在早期，手写准确率、Wayland 和应用兼容需要继续改进；欢迎带复现步骤的反馈。源码与版本：https://github.com/managecode-commits/zhimo

避免“全平台完美支持”“识别率业内领先”“完全不会出错”等无证据宣传。不会自动向社区发帖、群发私信或批量邀请用户。

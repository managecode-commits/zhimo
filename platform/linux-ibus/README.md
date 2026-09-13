# Linux IBus adapter

依赖安装、用户级部署、验证和卸载的统一步骤参见
[全平台构建、安装与验收手册](../../docs/构建与验收.md#41-ibusubuntugnome-推荐)。

该适配器通过 `ctypes` 调用与其他平台共用的 Runtime C ABI；Python 层只转换 IBus 事件和视图动作，不包含拼音逻辑。当前支持中英文模式、Shift+Space 切换、数字键/鼠标选词、上下移动、候选翻页，以及密码、邮箱、URL、终端输入域和应用身份策略；Runtime 不可用时按键安全透传。

开发运行：

```bash
cargo build -p ime-ffi
SHURUFA_IME_LIBRARY="$PWD/target/debug/libime_ffi.so" \
SHURUFA_USER_DATA_DIR="/tmp/shurufa-ibus-dev" \
  python3 platform/linux-ibus/shurufa_ibus.py
```

生产 librime 构建可设置 `SHURUFA_RIME_SHARED_DIR` 和 `SHURUFA_RIME_USER_DIR`；二者同时
存在时适配器启动 `rime` 引擎，否则明确降级到内置参考拼音。

系统安装时应将脚本、动态库和替换了 `@SHURUFA_IBUS_EXEC@` 的组件 XML 安装到发行版规定目录，然后重启 IBus。当前自动验证只覆盖 Runtime ABI；完整 IBus 焦点、候选窗和应用兼容性必须在图形会话中测试。

当前用户安装脚本：

```bash
./platform/linux-ibus/install-user.sh
```

该操作会构建 release 动态库，将文件安装到用户的 XDG data 目录、重建用户 registry cache
并重启 IBus；开发验证不需要执行安装。可恢复卸载使用
`./platform/linux-ibus/uninstall-user.sh`，它把已安装文件改名为 `.disabled` 后重建缓存。

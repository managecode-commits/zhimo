<!-- Copyright © 2026 立方田 &lt;managecode@gmail.com&gt; -->
# ADR-0001：Rust 通用运行时与稳定 C ABI

- 状态：已接受
- 日期：2026-09-02

## 决策

通用运行时使用 Rust；Windows、Linux、Apple、Android 以及 C/C++ 语言引擎通过稳定 C ABI 接入。ABI 只暴露不透明句柄、标量、带显式长度的字节缓冲区和生命周期函数。

## 原因

输入法是常驻且进入高敏感文本路径的系统组件，内存安全和确定的资源所有权优先。C ABI 能避免 Rust、Swift、Kotlin/JNI、C++ ABI 互相绑定。

## 后果

需要维护 FFI 契约测试、生成平台绑定，并禁止在 ABI 边界传递 Rust 容器或异常。

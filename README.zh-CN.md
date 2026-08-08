<div align="center">

# Clash Meta AI

**一个可以对话的 Clash Meta for Android。**

用聊天完成配置、分流和节点操作 —— 或者长按任意节点，看它真实的协议细节。

[![License](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-5.0%2B-3ddc84.svg)](#安装)
[![Based on](https://img.shields.io/badge/based%20on-ClashMetaForAndroid%20v2.11.32-lightgrey.svg)](https://github.com/MetaCubeX/ClashMetaForAndroid)

[English](README.md) · **简体中文**

</div>

> [!IMPORTANT]
> 本项目是
> [MetaCubeX/ClashMetaForAndroid](https://github.com/MetaCubeX/ClashMetaForAndroid)
> 的**修改版**，不是上游项目，与 MetaCubeX 无关联、未获其背书。基于上游 `v2.11.32`
> 分支，**2026 年 8 月**起修改。与上游同样采用 [GPL-3.0](LICENSE) 授权 —— 本仓库即
> 任何由其构建的二进制的对应源代码。
>
> 上游本身的内容（Clash Meta 是什么、支持哪些协议、自动化 Intent、内核贡献方式等）
> 请看[上游 README](https://github.com/MetaCubeX/ClashMetaForAndroid#readme)。
> 本文只讲这个 fork 新增了什么。

---

## AI 助手

一个具备工具调用能力的智能体，覆盖配置文件、应用分流、VPN 设置、代理组、Provider、
连接和日志共 **32 项操作**。接入任意 OpenAI 兼容接口 —— 地址、模型、密钥都由你填，
不内置任何服务、不硬编码任何厂商。

它会**先读后写**：改动前先读取现状，每次配置变更都交给内置内核校验，保留备份，
失败自动回滚。

**三个快捷入口**，也可以直接说你想做什么：

| | |
|---|---|
| 从零创建配置 | 从空白开始生成一份可用配置 |
| 按已安装应用配置分流 | 读取你真实安装的应用来规划分流 |
| 检查当前代理状态 | 只读诊断配置、VPN、网络和连接 |

### 改动之前会先问你

32 个工具各自声明了风险等级，授权闸门**在代码层**按这个等级执行 —— 提示词说破天也绕不过去。

| 模式 | 行为 |
|---|---|
| **谨慎** | 任何改动都要确认 |
| **均衡**（默认） | 常规改动直接执行，高风险改动需确认 |
| **完全自动** | 不再询问 |

只读操作始终放行。授权**按次生效**，不存在"以后都允许"。如果等待授权时界面被关闭，
该操作会被拒绝，而不是悄悄执行。

### 它汇报的是真实发生的事

运行卡片的标题由**工具返回结果**生成，而不是模型说了什么 —— 所以会显示
`已完成 · 写入 2 项` 或 `已完成 · 未修改任何配置`。如果回答声称改了什么但实际没执行，
标题会当场戳穿。展开卡片能看到每一步及其结果。

这份记录还会在后续轮次回放给模型，所以**某一轮只是"声称"写入过，不会变成下一轮的既成事实**。

### 接口

支持 **Chat Completions** 和 **Responses API** 两种格式，流式输出。API Key 用
Android Keystore 中的密钥加密存储，并与对话历史一起被排除在云备份之外。

## 代理节点详情

节点卡片带协议标签（`VMESS`、`TROJAN`、`SS`…）。**长按**任意节点，查看从内核实时读取的
真实配置 —— 加密方式、传输层、TLS、SNI、指纹、ALPN 等。链式节点会渲染成从入口到出口的
分段视图。点击任意行可复制；凭据在离开内核前就已脱敏。

## 稳定性

记录 VPN 进程的退出原因 —— 内存不足被杀、ANR、原生/Java 崩溃、用户主动停止 ——
并在服务被系统意外杀死后自动恢复。

## 安装

前往 [**Releases**](../../releases) 下载 APK。

包名是 `io.github.viewer12.cmfa.agent`，因此会**与已安装的 Clash Meta 并存**，
不会覆盖它。本 fork 使用自有密钥签名，无法用于更新上游版本。

Android 5.0 以上（建议 7.0+），`arm64-v8a`。

## 隐私

> [!WARNING]
> 助手会把所需内容发送到**你自己配置**的接口。涉及修改配置时，这包括
> **完整的 YAML —— 其中含有节点密码、UUID 和订阅链接**。

在你配置模型之前不会发送任何内容，也**永远不会发给本项目**（本项目不运行任何服务器）。
这些数据的处理受你所选模型服务商的隐私政策约束。详见
[PRIVACY_POLICY.md](PRIVACY_POLICY.md)。

## 构建

```bash
git submodule update --init --recursive
./gradlew :app:assembleAgentDebug -PagentArm64Only=true
```

需要 JDK 21、Android SDK、CMake 和 Go。`-PagentArm64Only=true` 只编译
`arm64-v8a`，快很多。

发布版构建需要你自己的签名密钥 —— 仓库里那个是从上游继承的、**公开的**，不可用于分发：

```bash
scripts/generate-release-key.sh
```

详见 [docs/SIGNING.md](docs/SIGNING.md)。

## 许可

[GPL-3.0](LICENSE)，与上游一致。第三方组件署名见 [NOTICE](NOTICE)。

基于 MetaCubeX 的
[ClashMetaForAndroid](https://github.com/MetaCubeX/ClashMetaForAndroid) 与
[mihomo](https://github.com/MetaCubeX/mihomo) 内核构建。

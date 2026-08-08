<div align="center">

# Clash Meta AI

**A Clash Meta for Android build you can talk to.**

Configure profiles, routing and proxies by conversation — or inspect any node's
real protocol details with a long press.

[![License](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-5.0%2B-3ddc84.svg)](#install)
[![Based on](https://img.shields.io/badge/based%20on-ClashMetaForAndroid%20v2.11.32-lightgrey.svg)](https://github.com/MetaCubeX/ClashMetaForAndroid)

**English** · [简体中文](README.zh-CN.md)

</div>

> [!IMPORTANT]
> This is a **modified fork** of
> [MetaCubeX/ClashMetaForAndroid](https://github.com/MetaCubeX/ClashMetaForAndroid),
> not the upstream project, and is not endorsed by MetaCubeX. Forked from upstream
> `v2.11.32`; modifications began **August 2026**. Licensed under
> [GPL-3.0](LICENSE) like upstream — this repository is the corresponding source
> for any build made from it.
>
> For everything inherited from upstream — what Clash Meta is, supported
> protocols, automation intents, kernel contribution — see the
> [upstream README](https://github.com/MetaCubeX/ClashMetaForAndroid#readme).
> This document only covers what the fork adds.

---

## AI assistant

A tool-calling agent with **32 operations** across profiles, per-app routing, VPN
settings, proxy groups, providers, connections and logs. Point it at any
OpenAI-compatible endpoint — you supply the URL, model and key; nothing is
hardcoded and no service is bundled.

It reads state before it writes, validates every profile change against the
bundled core, keeps backups, and rolls back if a change fails to apply.

**Three starting points**, or just type what you want:

| | |
|---|---|
| 从零创建配置 | Build a working profile from scratch |
| 按已安装应用配置分流 | Plan per-app routing from your real installed apps |
| 检查当前代理状态 | Read-only diagnosis of config, VPN, network and connections |

### It asks before it changes things

Each of the 32 tools declares a risk level. The approval gate is enforced **in
code** from that level, so no prompt can talk its way past it.

| Mode | Behaviour |
|---|---|
| **谨慎** Cautious | Every change needs confirmation |
| **均衡** Balanced *(default)* | Routine changes run; high-risk ones ask |
| **完全自动** Full auto | Nothing asks |

Read-only operations always run. Approval is granted per operation — never "always
allow". If the screen is closed while a run is waiting for approval, the operation
is denied rather than silently performed.

### It reports what actually happened

The run header is built from tool results, not from the model's prose — so it reads
`已完成 · 写入 2 项` or `已完成 · 未修改任何配置`. If a reply claims a change that
never executed, the header contradicts it. Expanding the card shows every step and
its outcome.

That record is also replayed to the model on later turns, so a turn that only
*claimed* to have written something cannot become evidence that it did.

### Endpoints

Both **Chat Completions** and **Responses API** formats, streamed. Your API key is
encrypted with a key held in the Android Keystore, and is excluded from cloud
backup along with conversation history.

## Proxy node details

Nodes carry a protocol badge (`VMESS`, `TROJAN`, `SS`…). **Long-press** any node
for its real configuration read live from the core — cipher, transport, TLS, SNI,
fingerprint, ALPN and more. Chained nodes render as a hop-by-hop stepper from entry
to exit. Tap a row to copy it; credentials are masked before they ever leave the
core.

## Reliability

Records why the VPN process died — low-memory kill, ANR, native or Java crash, user
stop — and recovers a service the system killed unexpectedly.

## Install

Download an APK from [**Releases**](../../releases).

The package is `io.github.viewer12.cmfa.agent`, so it **installs alongside** an
existing Clash Meta instead of replacing it. Builds are signed with this fork's own
key and cannot update an upstream install.

Android 5.0+ (7.0+ recommended), `arm64-v8a`.

## Privacy

> [!WARNING]
> The assistant sends what it needs to the endpoint **you** configure. For profile
> edits that includes the **full YAML — proxy passwords, UUIDs and subscription
> URLs among it**.

Nothing is sent until you configure a model, and nothing is ever sent to this
project — it operates no server. Your model provider's policy governs that data.
See [PRIVACY_POLICY.md](PRIVACY_POLICY.md).

## Build

```bash
git submodule update --init --recursive
./gradlew :app:assembleAgentDebug -PagentArm64Only=true
```

Requires JDK 21, Android SDK, CMake and Go. `-PagentArm64Only=true` builds only
`arm64-v8a` and is much faster.

Release builds need your own signing key — the key committed to this repository is
inherited from upstream and public:

```bash
scripts/generate-release-key.sh
```

See [docs/SIGNING.md](docs/SIGNING.md).

## License

[GPL-3.0](LICENSE), same as upstream. Third-party attributions are in
[NOTICE](NOTICE).

Built on [ClashMetaForAndroid](https://github.com/MetaCubeX/ClashMetaForAndroid) by
MetaCubeX and the [mihomo](https://github.com/MetaCubeX/mihomo) core.

# Releasing

## Version scheme

Two numbers move independently, and conflating them is the mistake this
document exists to prevent.

```
2.11.32  -ai.1
└──┬───┘ └─┬──┘
   │       └─ fork release, bumped every time you publish
   └───────── upstream baseline this is built on
```

| | Where | When it changes |
|---|---|---|
| `versionName` | `build.gradle.kts`, `defaultConfig` | Only when merging a new upstream release |
| `versionNameSuffix` | `build.gradle.kts`, the `agent` flavour | Every published release |
| `versionCode` | derived, `211032 + git rev-list --count HEAD` | Automatic — never edit |

`versionCode` is what Android compares to decide whether an APK is an update.
It is derived from the commit count so it can only ever grow and cannot be
forgotten. **Do not** switch it to something tag-derived: fork and upstream
version numbers do not line up, and a collision means users cannot update.

### Worked examples

| Situation | versionName | suffix | Tag |
|---|---|---|---|
| First release | `2.11.32` | `-ai.1` | `v2.11.32-ai.1` |
| Bug fix, same upstream | `2.11.32` | `-ai.2` | `v2.11.32-ai.2` |
| Merged upstream 2.11.33 | `2.11.33` | `-ai.1` ← reset | `v2.11.33-ai.1` |

The tag is always `v` + the full versionName. The release workflow reads
versionName back out of the built APK and refuses to publish if the two
disagree, so a forgotten suffix bump fails the job instead of shipping two
different builds under one version.

## Publishing

Actions → **Release Agent APK** → Run workflow → enter the tag, e.g.
`v2.11.32-ai.1`.

The job builds, then refuses to publish unless all four hold:

1. versionName in the APK matches the tag
2. every APK filename contains that version
3. the signer fingerprint matches `AGENT_RELEASE_CERT_SHA256`
4. `geoip.metadb`, `geosite.dat` and `ASN.mmdb` are inside the APK

Check 3 exists because `downloadGeoFiles` is wired to `assemble*` rather than
to `mergeAssets`; on a clean checkout the merge can run first and produce an
APK with no routing data, which looks fine until rules silently stop matching.

### What gets published

| APK | Size | Who it is for |
|---|---|---|
| `cmfa-<version>-agent-arm64-v8a-release.apk` | ~44 MB | Every phone from roughly 2017 on |
| `cmfa-<version>-agent-universal-release.apk` | ~100 MB | Anything not arm64 — carries all four ABIs |

`<version>` is the full versionName including the fork release number, e.g.
`cmfa-2.11.32-ai.1-agent-arm64-v8a-release.apk`. Check 2 above exists because
`archivesBaseName` is fixed before flavour suffixes are applied, so the naming
is overridden per variant; if that override is ever lost, two releases would
ship under one filename.

`armeabi-v7a`, `x86` and `x86_64` are built (universal is assembled from them)
but not published: a 32-bit or x86 user is served by universal without having
to know their architecture, and two files keeps the release page unambiguous.
Publish a specific split as well if someone actually asks.

## Required secrets

| Secret | Value |
|---|---|
| `AGENT_RELEASE_KEYSTORE` | `base64 -i ~/keys/cmfa-ai-release.keystore` |
| `AGENT_RELEASE_STORE_PASSWORD` | keystore password |
| `AGENT_RELEASE_KEY_ALIAS` | `cmfa-ai` |
| `AGENT_RELEASE_KEY_PASSWORD` | key password |
| `AGENT_RELEASE_CERT_SHA256` | signer fingerprint, lowercase hex, no colons |

The fingerprint is printed by `scripts/generate-release-key.sh`, or:

```bash
apksigner verify --print-certs some-release.apk | grep -i "SHA-256 digest"
```

See [SIGNING.md](SIGNING.md). The key committed to this repository is inherited
from upstream and public — never release with it.

## Building a release locally

Only needed when the workflow cannot run. It signs with whatever
`signing.properties` points at, and performs none of the three checks above.

```bash
git submodule update --init --recursive
./gradlew :app:assembleAgentRelease          # note: no -PagentArm64Only
```

`-PagentArm64Only=true` exists for fast test builds and produces arm64 only,
which means no universal APK. Never use it for a release.

Output lands in `app/build/outputs/apk/agent/release/`. Verify by hand before
uploading anything:

```bash
APK=app/build/outputs/apk/agent/release/*arm64-v8a*.apk
aapt2 dump badging $APK | grep '^package'                    # versionName, versionCode
apksigner verify --print-certs $APK | grep -i "SHA-256"      # your key?
unzip -l $APK | grep assets/geoip.metadb                     # geo data present?
```

## After merging upstream

1. Set `versionName` to the new upstream version.
2. Reset the agent `versionNameSuffix` to `-ai.1`.
3. Confirm `versionCode` went up — it will have, since merging adds commits.

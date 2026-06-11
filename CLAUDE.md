# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A thin Android **WebView shell** that wraps a remote H5 game website into a native APK. Based on the `slymax/webview` template. The entire app is two Java files — there is essentially no business logic; the app loads a URL into a full-screen WebView and ships it as a branded, per-channel APK.

The real "product" of this repo is the **build configuration**, not the code. Each release is a re-skin: a different target URL, app name, icon, package id, and signing key. Understand the customization points below before changing anything.

## Per-channel customization (the core workflow)

Channels are modeled as Gradle **product flavors** under the `agent` dimension in `app/build.gradle`. The `wechat` flavor is the existing example. To produce a new branded build, change these four things (see also `README_副本.md`):

| What | Where |
|------|-------|
| Target H5 URL | `buildConfigField "URL_ENTRY"` in the flavor block of `app/build.gradle` |
| Package id (`applicationId`) | the flavor block in `app/build.gradle` |
| App display name | `app_name` in `app/src/main/res/values/strings.xml` |
| Launcher icon | `app/src/<flavor>/res/mipmap-*/ic_launcher.png` |

`MainActivity` loads `BuildConfig.URL_ENTRY` at startup, so the URL is injected purely through the flavor — do not hardcode URLs in Java. Adding a new channel = adding a new flavor block, not editing `MainActivity`.

## Architecture notes

- `MainActivity.java` — configures the WebView (JavaScript on, DOM storage on, `LOAD_NO_CACHE`, file/content access on) and loads `BuildConfig.URL_ENTRY`. Back button navigates WebView history before exiting. A commented-out `loadUrl("file:///android_asset/index.html")` line is the alternate "local HTML5 app" mode using `app/src/main/assets/index.html`.
- `MyWebViewClient.java` — keeps `http(s)` navigation inside the WebView, but routes `weixin://` and `alipays://` deep links out to external apps via an `ACTION_VIEW` intent. Extend this method when adding support for other custom URL schemes (payment/wallet redirects).
- `App.java` — `Application` subclass that boots the **Gravity Engine (引力引擎) analytics SDK** in `onCreate` (see below). Registered via `android:name=".App"` in the manifest.
- Java package is `com.main.app` (fixed); the shipped `applicationId` differs per flavor.

## Gravity Engine (引力引擎) SDK

Analytics/attribution SDK integrated for buy-volume (买量) & ROI tracking. Official doc: `https://help.gravity-engine.com/docs/android`.

- **Init**: `App.java` calls `GEConfig.getInstance(...)` → `GravityEngineSDK.setupAndStart(config)` → `instance.initialize(...)` in `Application.onCreate`. No privacy popup exists, so `onCreate` is the earliest valid call site; if a consent dialog is ever added, move `setupGravityEngine()` to after consent.
- **Per-channel config is injected via `buildConfigField`** (same philosophy as `URL_ENTRY`) — never hardcode in Java:
  - `GE_ACCESS_TOKEN` — project pass-token from 设置-应用管理.
  - `GE_CHANNEL` — init channel (e.g. `wechat`).
- **Dependencies** (domestic build): `cn.gravity.android:GravityEngineSDK:5.0.31` + `com.huawei.hms:ads-identifier` + `com.hihonor.mcs:ads-identifier` (the last two boost OAID-based attribution). For an overseas build use `oversea.gravity.android:GravityEngineSDK` and add `com.android.installreferrer:installreferrer`. Check `https://help.gravity-engine.com/docs/android-release-notes` for the latest version.
- **Repos**: added to `allprojects.repositories` in root `build.gradle` (gravity nexus + huawei + hihonor maven).
- **minSdk**: the SDK requires `minSdkVersion >= 19`, so `PROP_MIN_SDK_VERSION` was raised from 16 to 19.
- **Permissions/queries** (network state, `READ_PHONE_STATE`, OAID `<queries>`, etc.) come from the SDK AAR's own manifest via manifest merge — they are NOT re-declared in the app manifest.
- **Event reporting** (not yet wired up — call on the saved `GravityEngineSDK` instance when those business events occur): `trackRegisterEvent()`, `trackPayEvent(...)`, `trackAdShowEvent(...)`, `trackWithdrawEvent(...)`. Since this is a WebView shell, these would typically be triggered from H5 via a JS bridge.

## Build & run

No test suite, no lint configuration, no CI — `./gradlew test` will find nothing. Builds are Gradle + Android flavors. The variant name is `<flavor><BuildType>`, e.g. `wechatDebug`, `wechatRelease`.

```bash
./gradlew assembleWechatDebug      # build debug APK for the wechat flavor
./gradlew assembleWechatRelease    # build release APK
./gradlew installWechatDebug       # build + install on a connected device/emulator
./gradlew clean                    # clear app/build
```

Outputs land in `app/build/outputs/apk/<flavor>/<buildType>/`. The committed reference APK is at `app/wechat/release/`.

## Signing

Release keystores live in `app/sign/release/` (debug key in `app/sign/debug/`). Credentials for each key are in the `签名参数*.txt` files alongside them. **Note:** `app/build.gradle` does not currently wire a `signingConfig` into `buildTypes.release` — release builds are signed manually/externally (`app/sign/signapk.jar` is present for this). If automating signing, add a `signingConfigs` block referencing the appropriate keystore.

## Environment expectations

- SDK / build versions come from `gradle.properties`: `compileSdk`/`targetSdk` 32, `minSdk` 16, Android Gradle Plugin 7.4.1.
- `local.properties` pins `sdk.dir` to a local Android SDK path and is git-ignored — it must exist for Gradle to run.
- `usesCleartextTraffic="true"` is set in the manifest, so plain-`http` game URLs work.
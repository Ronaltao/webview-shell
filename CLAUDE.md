# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A thin Android **WebView shell** that wraps a remote H5 game website into a native APK. Based on the `slymax/webview` template. The app is a handful of small Java files — there is essentially no business logic; it loads a URL into a full-screen WebView (behind a first-launch privacy dialog) and ships it as a branded, per-channel APK.

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
- `App.java` — `Application` subclass holding the analytics SDK init methods: `setupGravityEngine()` (引力引擎) and `setupBytedanceConvert(Activity)` (巨量转化). Both are **consent-gated** (see Privacy consent below) — not called unconditionally in `onCreate`. Registered via `android:name=".App"` in the manifest.
- `PrivacyManager.java` — tiny SharedPreferences wrapper (`hasAgreed`/`setAgreed`) that is the single source of truth for privacy consent, read by both `App` and `MainActivity`.
- Java package is `com.main.app` (fixed); the shipped `applicationId` differs per flavor.

## Privacy consent (隐私弹窗)

Both analytics SDKs collect device identifiers (OAID / AndroidID), so per PIPL they must init **only after** the user agrees to the privacy policy. The flow:

- First launch (`!PrivacyManager.hasAgreed`): `MainActivity` shows a framework `AlertDialog` (`showPrivacyDialog()`) with clickable 《隐私政策》/《用户协议》 spans that open an in-app WebView. **Disagree** → `finishAffinity()` (no SDK init, game not loaded). **Agree** → `PrivacyManager.setAgreed()` → init both SDKs → `loadGame()`.
- Returning user (`hasAgreed`): `App.onCreate` inits Gravity early; `MainActivity.onCreate` inits 巨量 (needs an `Activity`, so it can't run in `App.onCreate`) then loads the game.
- The 《隐私政策》/《用户协议》 link URLs are injected per-flavor via `PRIVACY_POLICY_URL` / `USER_AGREEMENT_URL` `buildConfigField`s (currently `https://example.com/...` placeholders — replace with real URLs).
- **Inner-class caveat**: AGP 7.4.1's bundled R8 4.0.48 throws a `NullPointerException` when dexing **anonymous** inner classes. All listeners / `ClickableSpan`s / SDK callbacks here are written as **named `static` nested classes** to avoid it — keep it that way.

## Gravity Engine (引力引擎) SDK

Analytics/attribution SDK integrated for buy-volume (买量) & ROI tracking. Official doc: `https://help.gravity-engine.com/docs/android`.

- **Init**: `App.java` calls `GEConfig.getInstance(...)` → `GravityEngineSDK.setupAndStart(config)` → `instance.initialize(...)` inside `setupGravityEngine()`. This is **consent-gated**: `App.onCreate` only calls it when `PrivacyManager.hasAgreed()`; on first launch it runs from the privacy dialog's Agree handler (see Privacy consent above).
- **Per-channel config is injected via `buildConfigField`** (same philosophy as `URL_ENTRY`) — never hardcode in Java:
  - `GE_ACCESS_TOKEN` — project pass-token from 设置-应用管理.
  - `GE_CHANNEL` — init channel (e.g. `wechat`).
- **Dependencies** (domestic build): `cn.gravity.android:GravityEngineSDK:5.0.31` + `com.huawei.hms:ads-identifier` + `com.hihonor.mcs:ads-identifier` (the last two boost OAID-based attribution). For an overseas build use `oversea.gravity.android:GravityEngineSDK` and add `com.android.installreferrer:installreferrer`. Check `https://help.gravity-engine.com/docs/android-release-notes` for the latest version.
- **Repos**: added to `allprojects.repositories` in root `build.gradle` (gravity nexus + huawei + hihonor maven).
- **minSdk**: the SDK requires `minSdkVersion >= 19` (raised from the template's 16; later bumped again to 21 for 巨量 — see below).
- **Permissions/queries** (network state, `READ_PHONE_STATE`, OAID `<queries>`, etc.) come from the SDK AAR's own manifest via manifest merge — they are NOT re-declared in the app manifest.
- **Event reporting**: 付费/注册 are wired through `WebAppBridge` (see JS bridge below). `App.trackPurchase(...)` calls `geInstance.trackPayEvent(payAmount分, payType, orderId, payReason, payMethod)` and `App.trackRegister(...)` calls `geInstance.trackRegisterEvent()` on the saved instance. Other preset events (`trackAdShowEvent(...)`, `trackWithdrawEvent(...)`) are not wired yet — add wrappers the same way.

## 巨量引擎 (Ocean Engine) 转化 SDK — AppConvert / BDConvert

ByteDance buy-volume attribution SDK ("巨量归因方案", domestic non-融合 / 转化SDK flow). Init mirrors the Gravity Engine philosophy but differs in two key ways: it needs an `Activity`, and it carries **no code-level AppID** — attribution is keyed off the `applicationId`, registered as an "资产" (asset) in the 巨量 platform.

- **Init**: `App.setupBytedanceConvert(Activity)` calls `BDConvert.INSTANCE.init(this, activity)` (官方「接入方式A」). `BDConvert` is a Kotlin object — from Java it's `BDConvert.INSTANCE`. Init auto-sends the launch event and collects OAID/AndroidID, so it is **consent-gated**. Because it requires an `Activity`, it is called from `MainActivity` (both the agreed-returning-user path in `onCreate` and the dialog's Agree handler), NOT from `App.onCreate`. Do **not** set `autoSendLaunchEvent(false)` under 方式A.
- **No `buildConfigField`** is needed for it (no AppID/token in code) — the platform attributes by package name. The register/purchase events do take a channel string (`"wechat"`), relevant only once events are wired.
- **Dependency**: `com.bytedance.ads:AppConvert:2.0.4`. **Repo**: `https://artifact.bytedance.com/repository/Volcengine/` added to `allprojects.repositories`. Proguard rules are bundled in the AAR — the host does **not** add any. Check the Lark doc for newer versions.
- **minSdk**: AppConvert 2.0.4 requires `minSdkVersion >= 21`, so `PROP_MIN_SDK_VERSION` was raised 19 → 21.
- **Platform setup** (outside the code): 巨量后台 → 资产 → 新建安卓应用资产 (fill `applicationId`) → 数据检测选「转化SDK」→ 调试事件.
- **Event reporting**: 付费/注册 are wired through `WebAppBridge`. `App.trackPurchase(...)` calls `ConvertReportHelper.onEventPurchase(contentType, contentName, contentId, contentNumber, paymentChannel, currency, isSuccess, currencyAmount元)` and `App.trackRegister(...)` calls `ConvertReportHelper.onEventRegister(channel, success)`. `onEventV3(name, jsonObject)` (e.g. `game_addiction` 关键行为) is available for custom events but not wired yet.

## H5 → 原生 JS bridge (WebAppBridge)

`WebAppBridge.java` exposes payment/register reporting to the H5 game so business events reach **both** SDKs at once. `MainActivity` registers it via `mWebView.addJavascriptInterface(new WebAppBridge(this), "AndroidBridge")`, so H5 calls `window.AndroidBridge.reportPurchase(json)` / `reportRegister(json)` — each takes a **single JSON string** (adding fields never breaks the signature).

- **Flow**: bridge parses JSON → `((App) activity.getApplication()).trackPurchase(...)` / `trackRegister(...)` → fans out to 巨量 (`ConvertReportHelper.*`, static) + 引力 (saved `geInstance`, null-checked).
- **`reportPurchase` JSON**: `amount` (required, **单位「分」/cents** to preserve precision), `currency` (default `CNY`), `orderId`, `productName`, `contentType` (default `game`), `channel` (default `BuildConfig.GE_CHANNEL`), `success` (default `true`).
- **`reportRegister` JSON**: `channel` (default `GE_CHANNEL`).
- **Amount-unit reconciliation**: H5 sends 分; for 巨量 `currencyAmount` (整数元) the bridge passes `amount/100` (**sub-yuan is truncated — 巨量 API limitation**); 引力 `trackPayEvent` gets 分 directly.
- **Threading**: `@JavascriptInterface` methods run on the WebView JS binder thread, NOT the UI thread. Both SDKs queue events internally, so calls are made directly off that thread — no `runOnUiThread` (which would need an anonymous `Runnable`, hitting the R8 dex bug). Keep `WebAppBridge` a top-level named class for the same reason.
- **Consent**: bridge methods early-return unless `PrivacyManager.hasAgreed`; in practice the game (hence the bridge's reachability) only loads post-consent anyway.
- **Security**: `addJavascriptInterface` is visible to every page in the WebView, including the remote (possibly http) game URL. Bridge only fires analytics so risk is low; tighten by host-checking before reporting if needed.

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

- SDK / build versions come from `gradle.properties`: `compileSdk`/`targetSdk` 32, `minSdk` 21 (raised 16 → 19 for Gravity Engine, then 19 → 21 for 巨量 AppConvert), Android Gradle Plugin 7.4.1.
- `local.properties` pins `sdk.dir` to a local Android SDK path and is git-ignored — it must exist for Gradle to run.
- `usesCleartextTraffic="true"` is set in the manifest, so plain-`http` game URLs work.
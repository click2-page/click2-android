# click2 Android SDK

[![CI](https://github.com/click2-page/click2-android/actions/workflows/ci.yml/badge.svg)](https://github.com/click2-page/click2-android/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/page.click2/click2-android.svg)](https://central.sonatype.com/artifact/page.click2/click2-android)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

Open [click2](https://click2.page) links in your Android app: App Links, deferred deep links via the Play Install Referrer, and install/open attribution. About 60 KB, no HTTP library dependencies.

The iOS SDK is at [click2-page/click2-ios](https://github.com/click2-page/click2-ios).

## Install

From Maven Central:

```kotlin
dependencies {
    implementation("page.click2:click2-android:0.2.0")
}
```

**Requirements:** minSdk 23; Kotlin 2.2 or newer in the app; kotlinx-coroutines 1.11 or newer (the SDK depends on 1.11.0, so Gradle upgrades older versions).

## Set up

**1. App Links.** Add an intent filter to the Activity that handles deep links, with one host per build flavor:

```xml
<intent-filter android:autoVerify="true">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="https" android:host="@string/click2_host" />
</intent-filter>
```

In the click2 dashboard (Team settings → Android app), add the package name with its signing SHA-256. Use the live team for production and the test environment for staging and debug builds. Then check that the domain verified:

```bash
adb shell pm verify-app-links --re-verify <package>
adb shell pm get-app-links <package>      # the host should show "verified"
```

**2. Configure** in `Application.onCreate`:

```kotlin
Click2.configure(
    this,
    Click2Config(
        hosts = listOf(getString(R.string.click2_host)),   // acme.click2.page / acme-test.click2.page
        appVersion = BuildConfig.VERSION_NAME,
        logging = BuildConfig.DEBUG,
    ),
)
Click2.isTrackingEnabled = consent.adStorageAllowed      // keep in sync with your consent settings
```

**3. Handle links** in `onCreate` (when `savedInstanceState == null`) and in `onNewIntent`:

```kotlin
if (Click2.isClick2Link(intent)) {
    lifecycleScope.launch { route(Click2.handle(intent)) }
}

fun route(result: Click2Result) = when (result) {
    is Click2Result.OpenRoute -> openRoute(result.path)          // your app's router
    is Click2Result.OpenWeb ->
        if (result.inAppBrowser) openInAppBrowser(result.url) else openExternalBrowser(result.url)
    is Click2Result.Failed, Click2Result.NotAClick2Link -> Unit
}
```

**4. Deferred deep links.** Call once on the first screen. It returns a result only on the first launch after an install from a click2 link:

```kotlin
lifecycleScope.launch { Click2.checkDeferredLink()?.let(::route) }
```

- **Never lost:** the link from the install referrer is saved to disk before it's resolved. If resolving fails with `NETWORK_ERROR` or `SERVER_ERROR`, you get that `Failed` result and the link is retried on later launches (up to 5 attempts, within 7 days of the install).
- **Survives rotation:** the work runs in the SDK, not in your scope. If your coroutine is cancelled, the next `checkDeferredLink()` in the same process returns the result. Concurrent calls share one check; only one of them gets the result, later calls get `null`.
- **Install attribution** is sent in parallel and retried on later launches until the server accepts or rejects it. It's never sent once `isTrackingEnabled` is `false`, and a pending report is dropped then.
- **No replays:** the referrer is only used when the app was installed within `Click2Config.deferredLinkMaxAgeMillis` (default 7 days), so app updates don't replay it. The "already checked" flag is tied to the install time (`PackageInfo.firstInstallTime`); a flag restored by Auto Backup onto a new install counts as not checked.

**Migrating from your own deferred-link code.** If your app already handled the install referrer, call `Click2.markDeferredLinkChecked()` once for users who went through it, so they aren't routed again:

```kotlin
if (legacyPrefs.getBoolean("install_referrer_checked", false)) Click2.markDeferredLinkChecked()
```

**Java and non-coroutine code** can use `Click2.resolve(uri, callback)`, `Click2.handle(intent, callback)` and `Click2.checkDeferredLink(callback)`. The callbacks run on the main thread but aren't lifecycle-aware (they may run after the Activity is destroyed); in Kotlin, prefer the suspend functions with `lifecycleScope`.

## Notes

- Uses `HttpURLConnection` and `org.json` from Android, so it can't clash with the app's OkHttp or Ktor versions. The AAR is about 60 KB.
- `timeoutMillis` (1 to 60 000, default 10 000) is the total time for a call, retry included; `resolve` returns `Failed(NETWORK_ERROR)` when it runs out. A request is retried once, only when it never reached the server (connection refused, DNS failure, TLS handshake); a read timeout or an install report that was already sent is never repeated.
- Only the configured hosts are ever called. Hosts must be bare host names (`acme.click2.page`): a scheme, port, path, user info or `_` throws in `Click2Config`.
- Links are matched leniently: unencoded `|`, `{}` or `%` in the query still count. Only `https` links on the default port, without user info, match.
- `OpenWeb.url` is always an `http`/`https` URL, with invalid characters (e.g. spaces) percent-encoded. `Click2Link.webUrl`, `iosUrl` and `androidUrl` are `null` when the server sent none or a non-web URL. A link with an in-app route still routes when its web URL is bad; a link that needs a web URL and has none resolves to `Failed(SERVER_ERROR)`.
- Nothing is logged unless `logging = true` (logs include link URLs).
- **Backup:** the SDK's state is in the `page.click2.sdk` SharedPreferences file. Backing it up is safe (the deferred-link flags are tied to the install time), but you can exclude it from Auto Backup.

## Concepts

- **Hosts:** the app configures its team's link hosts. Production builds use your team's host, e.g. `acme.click2.page`; staging and debug builds use its test environment, e.g. `acme-test.click2.page`. The SDK ignores other URLs and only ever calls these hosts.
- **Result:** every link resolves to one of:
  - `OpenRoute(path)`: an in-app route for this platform, e.g. `product/123?src=email`.
  - `OpenWeb(url, inAppBrowser)`: `inAppBrowser = false` for "web only" links (open in the external browser), `true` for "mobile web only" links and links without an app route.
  - `Failed(reason)`: `unknown_link`, `invalid_link`, `server_error` or `network_error`. The app usually just stays where it is.
  - `NotAClick2Link`: handle the URL as before.
- **Deferred deep links:**
  - **Android:** exact, via the Play Install Referrer. The fallback page sends the link through the Play Store.
  - **iOS:** the fallback page copies the link to the clipboard before opening the App Store, and the app hands the pasted URL to the SDK (`UIPasteControl` avoids the paste prompt). There's no fingerprinting.
- **Tracking consent:** set `isTrackingEnabled = false` and links still work but nothing is recorded (the SDK sends the `X-Tracking-Disabled: 1` header).

## From Branch

| Branch | click2 |
|---|---|
| `Branch.getAutoInstance` / `initSession` | `Click2.configure(...)` |
| `$android_deeplink_path` / `$ios_deeplink_path` / `$deeplink_path` | `OpenRoute.path` (platform route already chosen) |
| `$web_only` + `$android_url` / `$ios_url` | `OpenWeb(url, inAppBrowser = false)` |
| `$mobile_web_only` | `OpenWeb(url, inAppBrowser = true)` |
| `~campaign`, `~channel`, `~feature` | `link.campaign`, `link.channel`, `link.feature` |
| `~referring_link` query merged into the path | already merged by the server |
| `disableTracking` / `setTrackingDisabled` | `isTrackingEnabled = false` |
| test key / `*.test-app.link` | test environment host `<team>-test.click2.page` |

## Development

```bash
./gradlew :click2:testDebugUnitTest
./gradlew :click2:publishToMavenLocal      # page.click2:click2-android:<version> in ~/.m2
```

`fixtures/` holds test cases shared with the iOS SDK (link matching, resolution, install referrer). Keep them identical in both repositories; change a fixture first, then the code. See [CONTRIBUTING.md](CONTRIBUTING.md).

The HTTP API the SDK talks to is described in [`spec/openapi.yaml`](spec/openapi.yaml).

## License

Apache License 2.0, see [LICENSE](LICENSE).

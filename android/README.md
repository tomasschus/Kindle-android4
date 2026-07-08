# Biblioteca PDF — Android app

A native Android e-reader that turns an old ~7" Android 4.x tablet into a
dedicated PDF reader, syncing documents/highlights/reading-progress from a
self-hosted web platform via the REST API in [`../docs/API.md`](../docs/API.md).

## Contents

- [Compatibility target](#compatibility-target)
- [Architecture / library choices](#architecture--library-choices)
- [Project layout](#project-layout)
- [Building](#building)
- [Configuring the server URL](#configuring-the-server-url)
- [Feature notes](#feature-notes-how-things-work)
- [Known limitations](#known-limitations)
- [Build verification performed in this sandbox](#build-verification-performed-in-this-sandbox)

## Compatibility target

- **`minSdkVersion 15`** (Android 4.0.3, ICE_CREAM_SANDWICH_MR1) — the actual
  hardware constraint from the brief.
- **`targetSdkVersion 19`** (Android 4.4, KitKat) — deliberately **not** set to
  a modern value. This is the one intentional deviation from "targetSdkVersion
  can be modern": keeping it low avoids a long tail of behavior changes that
  only matter for apps distributed to a fleet of current-generation devices
  through Google Play (runtime permission dialogs, scoped storage, background
  execution limits, mandatory notification channels, `usesCleartextTraffic`
  defaulting to `false`/network security config requirements, etc.). This app
  runs on one specific, physically-controlled, sideloaded tablet, so none of
  that machinery earns its complexity — targeting the device's actual OS
  version keeps behavior predictable and matches how this app will really be
  used. `compileSdkVersion` is still modern (34) so the build itself uses
  current tooling.
- All code paths that actually run are API 15-safe: no `java.time.*`, no
  `java.nio.charset.StandardCharsets` (added API 19; we use
  `getBytes("UTF-8")` literal strings instead), no `java.util.Objects`, no
  `android.graphics.pdf.PdfRenderer` (API 21+, explicitly disallowed by the
  brief), no lambdas-as-lambdas relying on API 24 functional interfaces (we
  use plain anonymous inner classes throughout for clarity/consistency, not
  because lambda syntax itself is unsafe — `javac`/D8 desugar that
  regardless of minSdk). `View.SYSTEM_UI_FLAG_*` fullscreen flags are gated by
  `Build.VERSION.SDK_INT` (see `ReaderActivity.setFullscreen`) since
  `SYSTEM_UI_FLAG_FULLSCREEN` is API 16+ and `SYSTEM_UI_FLAG_IMMERSIVE_STICKY`
  is API 19+; on bare API 15 we fall back to `HIDE_NAVIGATION` + `LOW_PROFILE`
  only.

## Architecture / library choices

| Concern | Choice | Why |
|---|---|---|
| UI toolkit | `com.android.support` (Support Library) **28.0.0**, not AndroidX | AndroidX's tooling (Jetifier, current AGP defaults, many libraries' Java 8 desugaring assumptions) gets fragile the further you get from its assumed minSdk/tooling baseline. The old Support Library is the well-trodden path for API 15 and is a drop-in, well-documented, stable target. `android.useAndroidX=false` / `android.enableJetifier=false` in `gradle.properties`. |
| Build system | Gradle **8.7** + AGP **8.5.2** | AGP does not hard-error on `minSdkVersion` below 21 (it's a Play-policy concern, not a build-tool one), so a modern AGP/Gradle pair can still target API 15 while giving a build that works with the JDK versions developers actually have installed today (11/17/21) instead of forcing an old JDK 8 toolchain. `compileSdkVersion 34`. |
| PDF rendering | `com.github.barteksc:android-pdf-viewer:3.2.0-beta.1` (AndroidPdfViewer, via JitPack) | Wraps PdfiumAndroid; explicitly documented by its own README as "Works on API 11 (Android 3.0) and higher." `android.graphics.pdf.PdfRenderer` (API 21+) is a non-starter per the brief. If `3.2.0-beta.1` proves unstable once actually built, `2.8.2` is the documented "more stable" fallback in the library's own README — swap the version string in `app/build.gradle`. |
| Networking | OkHttp **3.12.13** + `org.json` (built into Android) | OkHttp 3.12.x is the last release line supporting API 9+/Java 7 (3.13+ requires API 21+). `org.json` avoids pulling in Gson/Moshi and their reflection/annotation-processor baggage; Retrofit was skipped for the same reason (its modern versions assume much higher minSdk / Java 8 stdlib features) — a thin hand-written `ApiClient` (see `net/ApiClient.java`) is a small, auditable surface that maps 1:1 onto `docs/API.md`. |
| Local storage | Plain `SQLiteOpenHelper` (`data/DbHelper.java`), no Room | Room's annotation processor adds build fragility on an old AGP/Java toolchain for no real benefit at this scale (3 small tables). |
| Async | `AsyncTask` + a single-thread `ExecutorService` in `SyncManager` | Both have existed since API 3; no extra dependency, no need for coroutines/RxJava (which would pull in Kotlin stdlib / more Java 8 assumptions). |

## Project layout

```
android/
  build.gradle, settings.gradle, gradle.properties   — root Gradle config
  gradlew, gradlew.bat, gradle/wrapper/               — wrapper (see note below)
  app/
    build.gradle                                      — module config, dependencies
    proguard-rules.pro
    src/main/
      AndroidManifest.xml
      java/com/kindlereader/app/
        data/     — Document/Highlight/Progress models + DbHelper (SQLite)
        net/      — ApiClient (REST calls per docs/API.md), SyncManager (sync algorithm)
        ui/       — LoginActivity, LibraryActivity, SettingsActivity, ReaderActivity,
                    DocumentAdapter, HighlightOverlayView
        util/     — Prefs (SharedPreferences), IsoDate, Sha256, ColorModeHelper
      res/        — layouts, drawables (shape-based, no PNG/vector-drawable
                    assets needed), values (strings/colors/dimens/styles), menu
```

## Building

### 1. Get an Android SDK

You need a normal Android SDK install (e.g. via Android Studio, or
`sdkmanager`) with `platforms;android-34` and a recent build-tools version.
Point Gradle at it either via `ANDROID_HOME`/`ANDROID_SDK_ROOT`, or create
`android/local.properties` with:

```
sdk.dir=/path/to/your/Android/sdk
```

(`local.properties` is gitignored — it's machine-specific.)

### 2. Generate the Gradle wrapper jar

`gradle/wrapper/gradle-wrapper.properties`, `gradlew` and `gradlew.bat` are
committed, but **`gradle/wrapper/gradle-wrapper.jar` is not** — generating it
requires downloading a Gradle distribution zip, which this sandbox could not
reach (see [below](#build-verification-performed-in-this-sandbox)). With a
normal internet connection, from `android/`, run once:

```
gradle wrapper --gradle-version 8.7
```

(using any locally-installed `gradle`; this both fixes up the wrapper jar and
re-confirms the version pin). After that, `./gradlew` works normally.
Alternatively just use your own installed `gradle` directly, skipping the
wrapper entirely:

```
gradle assembleDebug
```

### 3. Build

```
cd android
./gradlew assembleDebug     # or: gradle assembleDebug
```

Output APK: `app/build/outputs/apk/debug/app-debug.apk`. Install with
`adb install app-debug.apk` (or copy to the tablet and open it there — must
allow "unknown sources" for sideloading on Android 4.x).

## Configuring the server URL

1. On first launch you land on the login screen: enter the server base URL
   (e.g. `https://your-server.example.com`), username and password, and tap
   **Log in**. This calls `POST /api/auth/login` and stores the returned JWT
   + the server URL in `SharedPreferences` (`Prefs` class).
2. The server URL can be changed later from **Library → menu → Settings**,
   without logging out (useful if your server's address changes but your
   session is still valid). **Settings → Log out** clears the stored token.

## Feature notes (how things work)

- **Sync** (`net/SyncManager.java`) implements the exact algorithm in
  `docs/API.md` §"Incremental sync": push local dirty highlights/progress
  first, then `GET /api/sync?since=lastSyncAt`, upsert documents (queuing
  background downloads for anything new/checksum-changed), apply document
  deletions, apply highlight upserts/tombstones, apply progress, then persist
  `serverTime` as the new cursor. Runs on a single background executor so
  overlapping sync requests don't race.
- **Downloads** (`ApiClient.downloadDocument`) support HTTP `Range` resume and
  compare the server's sha256 `checksum` against a locally-recomputed one
  (`util/Sha256.java`) to skip re-downloading unchanged files, per the API
  contract. Files live in app-private storage (`getFilesDir()/documents/`),
  so no storage permission is needed at any API level.
- **Reading modes** (`util/ColorModeHelper.java`): rather than hooking into
  AndroidPdfViewer's internals, we use `View#setLayerType(LAYER_TYPE_SOFTWARE,
  paint)` on the `PDFView` itself with a `Paint` carrying a
  `ColorMatrixColorFilter`. This filters *everything* PDFView draws — dark
  mode uses a luminance-invert matrix, grayscale uses `setSaturation(0)`,
  light mode uses no filter — without needing access to the library's
  internal bitmaps. This also conveniently keeps `PDFView` on a
  software-rendered canvas at all times, which the highlight-overlay
  touch-mapping (next point) depends on.
- **Highlighting** (`ui/HighlightOverlayView.java` + `ReaderActivity`):
  existing highlights are drawn by registering an `OnDrawListener` via
  `PDFView.Configurator#onDrawAll()`, which the library calls with a `Canvas`
  already translated so the current page starts at `(0,0)` and with the
  page's current on-screen `pageWidth`/`pageHeight` — so drawing a normalized
  `{x,y,w,h}` rect is just `rect * pageWidth/pageHeight`, no manual layout
  math needed. Creating a *new* highlight (drag-to-select, arm with the
  toolbar's Highlight toggle) needs the *inverse* mapping (screen touch →
  normalized page point); rather than reimplementing AndroidPdfViewer's
  internal page-offset math (private API), `HighlightOverlayView` captures
  `Canvas#getMatrix()` from that same `onDrawAll` callback and inverts it.
  This is exact by construction (it uses whatever transform the library
  itself applied) but depends on `Canvas#getMatrix()` being reliable, which
  the Android docs only promise for a software-rendered canvas — hence tying
  this to the same `LAYER_TYPE_SOFTWARE` setting used for reading modes. See
  [Known limitations](#known-limitations) for the one thing here that
  genuinely needs on-device verification. Highlights persist to SQLite
  immediately (`dirty=true`) and are pushed via
  `POST/PUT/DELETE /api/.../highlights` in the background
  (`SyncManager.pushHighlightsAsync`), tolerating being offline.
- **Fullscreen** (`ReaderActivity.setFullscreen`): `Build.VERSION.SDK_INT`-gated
  `View.SYSTEM_UI_FLAG_*` combination — full immersive-sticky + fullscreen +
  hide-navigation on API 19+, fullscreen + hide-navigation on API 16-18, and
  hide-navigation + low-profile only on bare API 15 (where
  `SYSTEM_UI_FLAG_FULLSCREEN` doesn't exist yet).
- **Offline-first**: the library screen always renders from local SQLite
  first (`LibraryActivity.reloadFromDb`), independent of whether a sync
  succeeds; `ReaderActivity` never requires network to open an
  already-downloaded PDF, read, or highlight. Sync failures are surfaced as a
  toast but never block the UI.
- **No thumbnailing in v1**: per the brief, thumbnails are a nice-to-have and
  were skipped to avoid the added complexity/risk of rendering pdfium pages
  just for a grid icon; the library grid uses a simple static document icon
  instead (`res/drawable/doc_icon.xml`).

## Known limitations

1. **Highlight touch-mapping needs on-device verification.** The
   `Canvas#getMatrix()`-based inverse mapping in `HighlightOverlayView`
   (described above) is, as far as can be reasoned from AndroidPdfViewer's
   actual source (fetched and read from GitHub during development — see
   below), geometrically correct. But it could not be compiled against the
   real library binary or exercised on a real device/emulator in the sandbox
   this was built in (no Android SDK was reachable at all — see next
   section). This is the single piece of the app most worth double-checking
   first when you get a real build running: draw a highlight, confirm it
   lands under your finger, and if it's off, the fix is local to
   `HighlightOverlayView.finishDrag()`/`screenToPageNormalized()`.
2. **In-memory highlight cache can go stale across a background id swap.**
   When an offline-created highlight (`local-<uuid>` id) is successfully
   pushed to the server, `SyncManager` swaps its SQLite row to the
   server-assigned id, but `ReaderActivity`'s in-memory
   `highlightsByPage` map (used for rendering + tap-to-delete hit-testing)
   isn't notified of that swap. In the narrow window between a successful
   background push and the next full sync/activity reload, deleting that
   specific highlight from the reader UI can fail to reach the server (the
   local DB delete still succeeds). Restarting the reader (which reloads
   from SQLite) always self-heals this.
3. **No document thumbnails** (see above — explicitly deferred, not required
   for v1).
4. **Single-column/simple grid layout only** — no tablet-specific multi-pane
   (e.g. list+reader side-by-side) layout; the brief's target is a single
   7" screen used one-app-at-a-time, so this wasn't prioritized.
5. **No automated tests.** Given the sandbox couldn't even resolve the
   Android Gradle Plugin (let alone run instrumented tests on a device/
   emulator), test scaffolding was judged lower-value than a complete,
   carefully-reasoned feature set; adding JVM unit tests for `DbHelper`
   (via Robolectric) and `ApiClient` (via MockWebServer) would be a
   reasonable next step once a real build environment is available.

## Build verification performed in this sandbox

**The project could not be fully built in this development sandbox — not
because of anything in the project itself, but because this sandbox's
network egress only allows a small, explicit allowlist of hosts, and none of
the hosts required to fetch an Android toolchain are on it.** This is
documented in detail so it's easy to distinguish "the project is broken" from
"the sandbox couldn't reach the internet":

- `dl.google.com` (and its `maven.google.com` alias, which 307-redirects to
  it) is **blocked** (`403` at the CONNECT-tunnel level). This is the *only*
  host that serves both Google's Maven repository (needed for the Android
  Gradle Plugin itself, and for the `com.android.support:*` artifacts) *and*
  the Android SDK component repository (`platforms`, `build-tools`, etc. —
  these aren't Maven artifacts at all, they're fetched by `sdkmanager`/AGP
  from a completely different mechanism, also under `dl.google.com`). Without
  it there is no way to obtain an `android.jar` to compile against, or the
  AGP plugin to run the build at all, regardless of which AGP/support-library
  versions are chosen.
- `jitpack.io` is **blocked** (`403`) — this is where
  `com.github.barteksc:android-pdf-viewer` is published, since it never made
  it to Maven Central.
- Third-party mirrors of Google's Maven repo were also tried and are blocked
  the same way: `maven.aliyun.com`, `repo.huaweicloud.com`.
- Actually running `./gradlew`/`gradle wrapper` also failed for an unrelated
  reason: Gradle's own distribution downloads (`services.gradle.org`) now
  307-redirect to `github.com/gradle/gradle-distributions/releases/...`,
  and generic `github.com` access is blocked too (confirmed across Gradle
  8.7 down to 5.6.4 — all recent-enough versions redirect through GitHub
  releases). `raw.githubusercontent.com`, however, **is** reachable, which is
  how the exact AndroidPdfViewer API surface used in `ReaderActivity`/
  `HighlightOverlayView` was verified against the library's real source
  (`PDFView.java`, `OnDrawListener.java`, `FitPolicy.java`, etc., fetched
  directly from `github.com/barteksc/AndroidPdfViewer`) instead of relying on
  memory.
- To isolate this to specifically the Google/JitPack/GitHub-releases hosts
  (rather than "the network is broken"), the non-Google part of the
  dependency graph was verified to resolve correctly: a scratch Gradle
  project depending on `com.squareup.okhttp3:okhttp:3.12.13` against
  `mavenCentral()` resolved cleanly (pulling in `com.squareup.okio:okio:1.15.0`
  as expected). `repo1.maven.org` (Maven Central) is reachable in this
  sandbox; it's specifically the Android-toolchain-hosting infrastructure
  that isn't.
- The actual failure captured from `gradle assembleDebug --stacktrace` in
  this sandbox:

  ```
  A problem occurred configuring root project 'KindleReader'.
  > Could not resolve all artifacts for configuration 'classpath'.
     > Could not resolve com.android.tools.build:gradle:8.5.2.
        > Could not get resource
          'https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/8.5.2/gradle-8.5.2.pom'.
           > Could not GET '...'. Received status code 403 from server: Forbidden
  ```

**What this means for you:** on a machine/CI runner with normal internet
access, `cd android && gradle wrapper --gradle-version 8.7 && ./gradlew
assembleDebug` (or opening the project in Android Studio, which will offer to
do the equivalent) should work as-is — nothing in the project depends on
anything beyond `google()`, `mavenCentral()`, and `jitpack.io`, all of which
are ordinary, currently-live repositories reachable from any normal network.

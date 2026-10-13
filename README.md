# Kairos

A calm, offline Android day planner. Kotlin, Compose Material 3, Room, Hilt and a pure Kotlin scheduler. Minimum Android 8.0 (API 26). The app has no Internet permission. Calendar events come from Android's local CalendarContract provider; Google Calendar must sync them separately.

## Build

Use JDK 21 and an Android SDK with platform 37.2 and build tools 37.0.0. Current stable tool versions are pinned in `gradle/libs.versions.toml`. Set `ANDROID_HOME` or create an ignored `local.properties` with `sdk.dir=/path/to/sdk`.

```sh
./gradlew :scheduler:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. Install with `adb install -r` on a connected Android device. Instrumented UI checks: `./gradlew :app:connectedDebugAndroidTest`.

## One fixed release key for Kairos and Kaizen

Create the key once, keep a secure offline backup, and use it for both apps. Losing this key prevents updates over existing installations and breaks the signature permission between the apps.

```sh
keytool -genkeypair -v -keystore tarun-release.jks -alias tarun \
  -keyalg RSA -keysize 4096 -validity 10000
# macOS
base64 -i tarun-release.jks | pbcopy
# Linux: copy this output into GitHub's secure secret editor
base64 -w 0 tarun-release.jks
```

In repository Settings → Secrets and variables → Actions, add `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`. Never commit the key or paste credentials into issues/chat. Configure the identical secrets in Kaizen's repository.

Every push to main, `v*` tag or manual workflow runs tests and uploads an installable development APK. Open the latest **Build APK** run under **Actions**, then download **kairos-debug-apk** under **Artifacts** and unzip it to get the APK. No signing secrets are needed for this download.

When all four release signing secrets are configured, the same workflow also builds a minified, signed release APK, uploads **kairos-apk**, and attaches it to a GitHub Release. Missing signing secrets skip the release steps while leaving the development APK available. Incorrect configured keys/passwords fail the release steps. Local release builds without signing environment variables are deliberately unsigned; they are not installable release builds.

GitHub runners generate development signing keys; separate runs can use different keys. For reliable updates without data loss and Kaizen pairing, use the shared production key and the release APK. Export data before replacing a development installation whose key differs from the downloaded APK.

```sh
KEYSTORE_PATH=/secure/path/tarun-release.jks \
KEYSTORE_PASSWORD=... KEY_ALIAS=tarun KEY_PASSWORD=... \
./gradlew :app:assembleRelease
```

CI uses `GITHUB_RUN_NUMBER` as the version code via `VERSION_CODE`, with version name `1.0.<number>`. Keep version codes increasing; never recreate the release key. Verify updates with `adb install -r` on two successive signed builds, checking retained tasks afterwards.

## Companion contract

See [KAIZEN_INTEGRATION.md](KAIZEN_INTEGRATION.md). This repository implements the consumer only. The Kaizen provider must be implemented in the separate Kaizen app. Manual check-ins and defaults work without Kaizen or calendar permission.

## Source specification

[SPEC.md](SPEC.md) is the requested behavior. [VALIDATION.md](VALIDATION.md) records passed checks and remaining device-dependent acceptance checks. Build/test success alone does not establish cold-start timing, notification delivery, accessibility, or companion-provider behavior on a real device.

## Architecture and development

- `scheduler/`: Android-free domain records, estimates, intervals, energy learning, deterministic seven-day planning, backup validation and contract parsing.
- `app/.../data/`: Room's typed record tables, relational identifiers/indexes, JSON domain payloads, transactional snapshots, DataStore and learning.
- `app/.../domain/`: serialized planning/session operations that preserve pinned/completed work and register reminders/widgets.
- `app/.../ui/`: onboarding, Today/Week, task and subtask editing/dragging, weekly rhythm grid/list, calendars, reminders, review and settings.
- `integration/`, `background/`, `reminders/`, `widgets/`, `backup/`: Android adapters and lifecycle work.

Schema 1 is the initial Room/JSON format. The exported Room schema is checked in. There is no destructive migration fallback. Add and test an explicit migration before changing the Room version or introducing a breaking backup format. Unknown backup versions fail before replacement; deleted-step identifiers remain valid in immutable work history.

Instrumented tests reset Kairos data. Run them only on a disposable emulator. The debug APK uses a development signing key. Before replacing a development installation with a production build signed by a different key, export your data; Android cannot update an installation across unrelated signing keys.

Kaizen must also request its own `READ_WELLBEING` permission (`<uses-permission>`), because Kairos protects the incoming update receiver with that signature permission. Its provider remains a change for the separate Kaizen repository.

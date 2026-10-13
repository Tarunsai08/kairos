# Validation and delivery

The source implements the Kairos build specification and the consumer side of the Kaizen contract. This report separates local build evidence from device and release checks that still need to run. No production signing key was created.

## Cloud toolchain

The prepared environment contains JDK 21, Gradle 9.8.1, Android platform 37.2, build tools 37.0.0, platform tools, and the cached project dependencies under `/workspace/tooling`. Activate them in each shell:

```sh
source /workspace/tooling/activate.sh
cd /workspace/kairos
```

JDK 21 is required by the selected current Android Gradle Plugin. The app targets API 37 and supports API 26 onwards. Official tool downloads were checked against their published checksums. Java uses the supplied proxy certificate through a separate truststore; TLS verification stays enabled.

## Passed checks

| Check | Evidence |
| --- | --- |
| Pure Kotlin tests | 38 tests, zero failures/errors: 26 scheduler, 7 backup, 4 companion contract, 1 overrun policy. XML reports in `scheduler/build/test-results/test/`. |
| Debug APK and Android test APK compilation | `:app:assembleDebug :app:assembleDebugAndroidTest` passed after the final application changes. |
| Debug lint | Zero errors; nine `UseKtx` suggestions. `app/build/reports/lint-results-debug.html`. |
| Final release build and lint | `:scheduler:test :app:assembleRelease :app:lintRelease` passed; R8 shrinking succeeded. Release lint has zero errors and nine `UseKtx` suggestions. |
| APK permissions and debug signature | `aapt dump permissions` confirms no `android.permission.INTERNET` in the minified release APK. `apksigner verify --verbose` verifies the development APK's v2 signature. |
| Room on Android | `StorageTest.transactionFlowsAndForeignKeyCascade` passed on the disposable AOSP API 35 emulator. It checks reactive writes, transactional snapshots, and foreign-key deletion. |
| Gradle wrapper and setup script | The checked-in wrapper and `/workspace/tooling/kairos-install.sh` were executed successfully with the installed SDK/JDK. |
| Source whitespace | `git diff --check` passed. |
| Actions development workflow | Workflow YAML, shell syntax, and `actionlint` passed. The exact development build command passed locally; GitHub execution is verified separately. |

The scheduler tests cover all required engine scenarios, including empty/fully booked days, today's/infeasible deadlines, no subtasks, low mood, pins, overruns, recurrence, learned corrections, and overlapping calendar/fixed commitments. Additional regressions check actual work against capacity, opt-in extra capacity, deterministic retry identities, deadline risk for preserved pins, and retained completed history.

Backup tests check round-trip data, unknown fields, unsupported schema versions, duplicate identifiers, invalid references/statistics, and history that refers to deleted steps. Schema 1 is the initial format; future breaking formats require an explicit migration. Invalid imports fail before replacement.

## Device test status

The environment has no `/dev/kvm`; the emulator uses CPU emulation and software rendering. Gradle's connected-device runner timed out while fetching Android properties before running tests. Native ADB installation and ahead-of-time compilation succeeded, and the native instrumentation runner subsequently executed both tests.

The Room test passed. The Compose onboarding → add task → manual low-mood check-in journey timed out waiting for the Compose/Espresso idle link. A captured screenshot also shows an Android launcher ANR. The UI journey is not recorded as passing: the timeout remains unresolved, and the emulator's poor responsiveness alone does not prove the application is correct. The application's periodic clock was moved into the ViewModel, and the test records stage markers for diagnosis.

After the environment restart, a smaller-display retry failed during APK installation with a package-service `Broken pipe` error. Android's watchdog log shows it killed the system process while initializing the native GPS service; System UI then reported `DeadSystemException`. The attempted `ro.hw_timeout_multiplier` emulator boot override was rejected because this emulator accepts only `qemu.*` boot properties. The final bounded eight-minute boot check also timed out before installing the latest APKs or executing UI tests. The test emulator was stopped afterwards. No passing UI result is inferred from these retries.

For a responsive disposable emulator or device:

```sh
./gradlew :app:connectedDebugAndroidTest
```

These tests reset Kairos data. They must not run against a personal installation. A native runner can also be used after installing both debug APKs:

```sh
adb shell am instrument -w -r \
  com.tarun.kairos.test/androidx.test.runner.AndroidJUnitRunner
```

Inspect the instrumentation summary: an ADB process exit code of zero does not establish that assertions passed.

## Remaining acceptance checks

| Requirement | Remaining validation |
| --- | --- |
| Signed GitHub Release and updates without data loss | Configure the same four signing secrets in Kairos and Kaizen, run the workflow, and install two successive builds over existing tasks. Signed release execution and update retention are not yet verified. Development APK artifacts are built independently of signing secrets. |
| Manual fallback and onboarding UX | Complete the Compose journey on a responsive device; test permission denial and Kaizen absent. |
| Kaizen provider/broadcast | Install the separate Kaizen app signed with the shared key and verify contract version 1, check-in changes, revoked access, and bad provider data. Only the Kairos consumer belongs to this repository. |
| Calendar timeline/live refresh | Exercise selected local calendars, recurring/all-day events, conversion to tasks, permission denial, and provider updates. Interval exclusion is covered by scheduler tests. |
| Notifications and widgets | Exercise notification actions, exact-alarm denial, quiet hours, reboot/time-zone rescheduling, widget resizing, and active-session refresh on Android. |
| SAF backups and Android Auto Backup | Exercise document creation, preview/restore/undo, persisted folder grants, four-file retention, and OS backup/restore. JSON validation is covered by JVM tests. |
| Accessibility and visual behavior | Check TalkBack, larger fonts, small screens, dark/light themes, timeline dragging, and subtask reordering. |
| Cold start <1 second; replanning <200 ms | Measure the release build on a representative mid-range phone with realistic stored data. The unaccelerated emulator is unsuitable for these performance claims. |

## Artifacts and signing

`app/build/outputs/apk/debug/app-debug.apk` is the installable development APK. Local release builds without the production signing variables produce `app/build/outputs/apk/release/app-release-unsigned.apk`; it cannot be installed as a signed release. Debug and production keys are different, so export development data before changing installations across keys.

Final artifact SHA-256 values:

```text
b92be82d7ccdabe7603dce38f1537c9aa2e9c93353dc0f874bf46132139391e5  app-debug.apk
2072f9d38267652d8482e35377687d3c084938bec66b8a1de160688d3fc609e1  app-release-unsigned.apk
```

See [README.md](README.md) for shared-key setup, CI instructions, and module responsibilities. Signing passwords and the release key belong in secure GitHub Actions secrets, not the cloud setup script or source tree.

The cloud configuration draft has saved `install_script` and `start_skill` fields. The refresh script passed with cached dependencies, and the startup instructions use the prepared filesystem snapshot. Review and publish the configuration through environment settings to make that snapshot reusable; saving the draft does not publish it.

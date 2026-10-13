# Build Prompt: Kairos — An On-Device Adaptive Day Planner for Android

> Paste this whole file into your AI coding assistant (or commit it to the repo root as `SPEC.md` and tell the assistant to follow it). "Kairos" is a working name; rename freely.

---

## 0. Your role and how to work

You are a senior Android engineer. Build a complete, production-quality Android app called **Kairos** from this spec.

Working rules:
- Write complete, compiling code. No `TODO` stubs, no placeholder functions, no "implement this later".
- Follow the build order in Section 14. Finish and verify each milestone before starting the next.
- If something in this spec is technically impossible or clearly worse than an alternative, say so, explain why, and propose the alternative before deviating.
- Use the latest **stable** versions of every library and tool. Do not use alpha/beta releases.
- Keep the code modular and readable. Put the scheduling logic in pure Kotlin (no Android dependencies) so it is unit-testable.

---

## 1. What the app does (one paragraph)

Kairos is a personal planner for a busy graduate student. The user enters fixed weekly commitments (classes, meals, bath, gym, sleep, calls), and tasks with deadlines, difficulty, and optional subtasks. Every day, Kairos reads the user's Google Calendar events and their wake-up time and mood (from a companion app called **Kaizen**), then builds a realistic plan for today and a tentative plan for the rest of the week. It puts hard work in the user's high-energy hours, lightens the load on low-mood days, and re-plans automatically when things slip. It is gentle, not bossy: every plan is a suggestion the user can edit. Everything runs fully **on-device**, with no paid APIs and no network calls.

---

## 2. Hard constraints

| Area | Requirement |
|---|---|
| Platform | Android only. `minSdk 26`, `targetSdk` = latest stable |
| Language / UI | Kotlin, Jetpack Compose, Material 3 |
| Network | **None.** No internet permission. No cloud APIs, no LLM APIs, no analytics |
| Intelligence | All planning is deterministic, rule-based + lightweight on-device learning (statistics, moving averages). No ML model downloads |
| Data | Local only (Room/SQLite), with user-controlled backup and restore |
| Distribution | Sideloaded APK built by GitHub Actions on every push (Section 13) |
| Time zone | Use the device's local time zone (user is in Asia/Kolkata, IST) via `java.time` |
| Cost | Zero running cost |

---

## 3. Tech stack

- **UI:** Jetpack Compose + Material 3, Material You dynamic color, light and dark themes
- **Architecture:** MVVM + clean layers (`ui` / `domain` / `data`), unidirectional data flow with `StateFlow`
- **DI:** Hilt
- **Database:** Room (with exported schemas and proper migrations from day one)
- **Settings:** DataStore (Preferences)
- **Background work:** WorkManager (daily sync, re-plan, backups)
- **Reminders:** AlarmManager exact alarms (`SCHEDULE_EXACT_ALARM`), falling back to inexact alarms if the permission is denied
- **Widgets:** Jetpack Glance
- **Calendar:** Android `CalendarContract` provider (reads Google Calendar events already synced on the device — no OAuth, no Google API)
- **Companion data:** Read-only `ContentProvider` exposed by the Kaizen app (Section 9)
- **Serialization:** kotlinx.serialization (for backups)
- **Testing:** JUnit5 or JUnit4, Turbine for flows, Compose UI tests for key screens
- **Build:** Gradle Kotlin DSL + version catalog (`libs.versions.toml`)

Package name: `com.tarun.kairos`

---

## 4. Data model

Design Room entities close to this. Adjust names if needed but keep the meaning.

### FixedBlock (recurring commitments)
- `id`, `title`, `type` (CLASS, MEAL, BATH, GYM, SLEEP, CALL, COMMUTE, OTHER)
- `daysOfWeek` (set), `startTime`, `endTime` (local times)
- `isFlexible` (bool) — e.g. gym can shift within a window; classes cannot
- `flexWindowStart`, `flexWindowEnd` (nullable)
- `validFrom`, `validUntil` (nullable — semesters end)
- `color`, `notes`

### Task
- `id`, `title`, `category` (ASSIGNMENT, EXAM_PREP, RESEARCH, CODING, SYSTEM_DESIGN, ALGORITHM, PERSONAL, OTHER)
- `deadline` (date-time)
- `difficulty` (1–5)
- `estimatedHours` (nullable — **optional**)
- `isRecurring` + `recurrenceRule` (e.g. "Learn one algorithm" every day, 1 hour)
- `status` (ACTIVE, DONE, ARCHIVED), `createdAt`, `notes`

### Subtask
- `id`, `taskId`, `title`, `order`, `difficulty` (1–5)
- `estimatedHours` (nullable), `status` (TODO, IN_PROGRESS, DONE)
- A task may have **zero** subtasks. The user often adds subtasks later.

### PlannedSession (a block of focused work in the plan)
- `id`, `date`, `start`, `end`
- `taskId`, `subtaskId` (nullable)
- `kind` (WORK, BREAK, EASY_WIN, PLAN_SUBTASKS, BUFFER)
- `reason` (short human text, e.g. "High-energy slot · deadline in 2 days")
- `isPinned` (true if the user edited/moved it — the re-planner must respect it)
- `state` (PLANNED, DONE, PARTIAL, SKIPPED)

### WorkLog (what actually happened)
- `sessionId`, `actualMinutes`, `completedAt`, `perceivedDifficulty` (optional 1–5)

### DayContext (one row per date)
- `date`, `wakeTime`, `previousBedtime`, `sleepHours`
- `moodMorning` (1–5, nullable), `moodNight` (1–5, nullable), `energy` (1–5, nullable)
- `source` (KAIZEN, MANUAL, DEFAULT)

### CalendarEventCache
- `eventId`, `calendarId`, `title`, `start`, `end`, `isAllDay`, `lastSynced`
- `treatAs` (BUSY, IGNORE, CONVERTED_TO_TASK)

### Learned stats
- `EstimateCorrection(category, factor)` — ratio of actual to estimated time
- `EnergyProfile(hourOfDay, weekday, score)` — learned focus quality per hour

---

## 5. Time estimation

When the user gives no hours, estimate them.

1. **Base hours by difficulty** (per subtask, or per task if there are no subtasks):

   | Difficulty | 1 | 2 | 3 | 4 | 5 |
   |---|---|---|---|---|---|
   | Base hours (subtask) | 0.5 | 1 | 2 | 3.5 | 5 |
   | Base hours (whole task, no subtasks) | 1 | 3 | 6 | 10 | 16 |

2. **Category multiplier** (starting defaults, user-editable in Settings): Exam prep 1.3, Research 1.4, Coding 1.3, Assignment 1.0, System design 1.0, Algorithm 1.0, Personal 0.8.
3. **Learned correction:** after each completed task/subtask, update `EstimateCorrection[category]` with an exponential moving average of `actual / estimated` (alpha = 0.3, clamp factor to 0.5–2.5).
4. If the user gives hours, use them, but still record actuals so the user can see how accurate their guesses are.
5. **Tasks without subtasks:** schedule the work as generic sessions ("Work on: DBMS Assignment"), and add one short `PLAN_SUBTASKS` session (15 min) early on, with a gentle nudge: "Break this into steps?". Never block planning because subtasks are missing.

Show every estimate in the UI as "~3h (estimated)" so the user knows it is a guess and can override it.

---

## 6. The scheduling engine (core of the app)

Implement as a pure Kotlin module `:scheduler` with no Android dependencies. Input: fixed blocks, calendar events, tasks/subtasks, day contexts, energy profile, settings, pinned sessions. Output: planned sessions for today + the next 6 days, each with a `reason`.

### 6.1 Build free time for each day
1. **Awake window:** from `wakeTime` (Kaizen → manual → typical wake time from settings) to planned bedtime (the SLEEP fixed block).
2. Subtract fixed blocks, calendar events marked BUSY, and pinned sessions.
3. Add a **10-minute transition buffer** around fixed blocks and events (configurable).
4. Drop free gaps shorter than 25 minutes (or use them only for EASY_WIN items ≤ 25 min).

### 6.2 Split free time into focus sessions
- Session length: **40–60 minutes** (default 50), followed by a **10-minute break**. After 3 sessions, insert a 20-minute long break.
- Cap deep work per day by **daily capacity** (default 6 focus hours on a normal day; configurable).

### 6.3 Energy model
- Default energy curve with three peaks: **early morning, evening, and night** (the user's reported peak times). Mid-afternoon is low.
- Learn over time: for each completed session, record hour-of-day and whether it was DONE/PARTIAL/SKIPPED. Update `EnergyProfile` with a moving average. Blend learned scores with defaults (weight on learned data grows with sample count; full trust after ~30 sessions in that hour bucket).
- The user's energy varies, so today's `energy` / `moodMorning` from Kaizen shifts the whole curve up or down.

### 6.4 Prioritizing work
For each remaining work unit (subtask or task chunk), compute a score:

```
remaining      = estimated remaining hours (after correction factor)
available      = free focus hours between now and (deadline - buffer)
pressure       = remaining / max(available, 0.1)        // > 1.0 means at risk
urgency        = 1 / (days until deadline + 1)
score          = 0.5 * pressure + 0.3 * urgency + 0.2 * (difficulty / 5)
```

- **Deadline buffer:** aim to finish each task **one day before its deadline** (or 15% of the time window for long tasks, whichever is larger).
- Respect subtask `order` within a task.
- Avoid scheduling the same task for more than 3 back-to-back sessions; interleave when possible to reduce fatigue.
- Recurring tasks (e.g. "Algorithm a day") get a fixed daily slot preference but can move within the day.

### 6.5 Placing work into slots (greedy, explainable)
1. Sort slots by energy score (high to low) and work units by score.
2. Place **hard** units (difficulty ≥ 4) into the **highest-energy** slots first.
3. Place medium units next, then easy units into low-energy slots.
4. Fill each day only up to today's capacity. Spread remaining work across the week so later days are not overloaded.
5. Generate a `reason` string for every session, e.g.:
   - "Peak focus slot · hardest step of OS Assignment"
   - "Easy win to get started"
   - "Due tomorrow — kept short because you slept 5h"

A greedy algorithm is enough. Do not add a heavy solver library.

### 6.6 Mood and sleep adaptation ("low day" mode)
Trigger low-day mode when **any** of: `moodMorning ≤ 2`, `sleepHours < 6`, or user taps "Low energy today".

In low-day mode:
- Reduce daily capacity to **60%**.
- Start the day with **1–2 easy wins** (difficulty ≤ 2, ≤ 30 min) to build momentum.
- Move difficulty ≥ 4 work to later days **only if** the deadline still stays safe; otherwise keep it but split into shorter (40 min) sessions with longer breaks.
- Show a soft message, e.g. "Lighter plan today. You've got this." No guilt, no streak shaming.

When `moodMorning ≥ 4` and sleep is good, allow capacity up to 110% and offer (not force) "Pull one task forward?".

Use `moodNight` + the day's completion rate to calibrate how many hours are realistic for tomorrow.

### 6.7 Infeasible deadlines
If a task cannot fit before its deadline even at full capacity:
- Mark it **At risk** in red-ish (but calm) styling.
- Show options: increase today's capacity, use flexible blocks (e.g. shorten gym), lower scope, or accept partial completion. Never silently drop work.

### 6.8 Re-planning triggers
Re-plan (keeping pinned and DONE sessions untouched) when:
- A new day starts / the user opens the app for the first time today
- Kaizen reports a new morning check-in
- Calendar events change (ContentObserver on `CalendarContract.Events.CONTENT_URI`)
- A session is skipped, marked partial, or overruns by > 10 min
- The user adds/edits a task, subtask, or fixed block

Re-planning only changes the **future** part of today and later days. Show a small, dismissible note: "Plan updated: moved 'Graph algorithms' to 8:30 PM".

### 6.9 Unit tests for the engine (required)
Write tests for at least: empty day, fully booked day, deadline today, infeasible deadline, task with no subtasks, low-mood day, pinned session respected, overrun triggers shift, recurring daily task, learned correction factor applied, events overlapping fixed blocks.

---

## 7. Screens and UX

### Design direction
Minimal, calm, and focused — in the spirit of Play Store Editor's Choice productivity apps such as **Structured**, **TickTick**, and **Todoist**. Guidelines:
- One clear primary action per screen. Lots of white space. No dashboards full of numbers on the main screen.
- **Vertical timeline** for the day, with colored blocks (fixed blocks muted, work sessions in the accent color).
- Material You dynamic color, perfect dark mode, rounded shapes, smooth but short animations, light haptics on completion.
- Large touch targets, readable type scale, TalkBack labels on everything.
- Adding a task must take under 15 seconds (bottom sheet, smart defaults).

### Screens
1. **Onboarding (first run, 4 short steps):** typical wake/sleep times → peak energy times → add fixed blocks (with quick templates for meals, bath, gym, sleep) → permissions (calendar, notifications, exact alarms) and Kaizen link status.
2. **Today (home):**
   - Top: **"Now" card** — current or next session, a reason line, buttons *Start*, *Done*, *Skip*, *Swap*.
   - Below: timeline of the day (fixed blocks, calendar events, sessions, breaks).
   - Small day header chip showing mood + sleep from Kaizen (e.g. "Mood 3 · Slept 6.5h"), tap to edit manually.
   - Long-press a session to move, resize, or pin it. Drag on timeline to reschedule.
3. **Week:** 7-day overview with load per day (light bar), deadlines marked, at-risk tasks flagged.
4. **Tasks:** list grouped by deadline (Today, This week, Later). Each shows progress, estimated remaining time, and risk status. Tap to open task detail with subtasks (reorder via drag, add quickly, set difficulty with 5 dots).
5. **Add / edit task sheet:** title, category, deadline (date + time), difficulty (5 dots), optional hours, optional subtasks (inline list), recurring toggle.
6. **Fixed schedule:** weekly grid of fixed blocks; add/edit with templates; semester date range.
7. **Review (weekly):** planned vs. done hours, best focus hours (from energy profile), estimate accuracy per category, tasks that slipped. Keep it to 4–5 simple cards.
8. **Settings:** session length, break length, daily capacity, deadline buffer, category multipliers, calendars to include, Kaizen link, quiet hours, backup & restore, theme.

---

## 8. Google Calendar sync

- Use `CalendarContract` with `READ_CALENDAR` permission. This reads events from Google Calendar accounts already synced on the phone. No OAuth and no network calls.
- Let the user choose which calendars to include (e.g. personal and IITB calendar).
- **Daily sync** via WorkManager at the user's wake time (default 5:30 AM), plus on app open, plus a ContentObserver for live changes.
- Timed events → BUSY blocks. All-day events → banner at top of the day (not blocking).
- Each event has an action **"Turn into task"** (pre-fills title and deadline). Events whose titles start with a configurable prefix (default `[T]`, e.g. "[T] Submit CS725 report") are auto-suggested as tasks.
- Note: Google **Tasks** are not available through `CalendarContract`; only calendar events. Mention this in Settings.

---

## 9. Kaizen integration (read-only)

Kaizen is the user's separate wellbeing app (mood, sleep, habits). It exposes a read-only `ContentProvider`. The exact contract is in `KAIZEN_INTEGRATION.md`; implement the **consumer** side here.

- Authority: `com.tarun.kaizen.provider`
- Permission: `com.tarun.kaizen.permission.READ_WELLBEING` (signature-level; both apps are signed with the same key)
- Read `content://com.tarun.kaizen.provider/checkins` filtered by date to get: wake time, bedtime, sleep hours, morning mood, night mood, energy (all on a 1–5 scale).
- Check `content://com.tarun.kaizen.provider/meta` for `contractVersion`; support version 1 and ignore unknown columns.
- Listen for the broadcast `com.tarun.kaizen.action.CHECKIN_UPDATED` (sent explicitly to `com.tarun.kairos`) and re-plan on receipt.
- **Fallbacks:** if Kaizen is not installed, the permission is missing, or today has no check-in, show a one-tap **Quick check-in** card in Kairos (wake time + mood 1–5) and use `DayContext.source = MANUAL`. If that is also skipped, use defaults.
- Wrap all provider access in a repository with proper error handling; Kairos must never crash because Kaizen is missing or returns bad data.
- Use `<queries><package android:name="com.tarun.kaizen" /></queries>` in the manifest for package visibility.

---

## 10. Notifications and widgets

### Notifications (gentle by default)
- **Morning:** "Your plan is ready — 4 focus sessions, first at 7:00 AM." (after wake time + Kaizen check-in)
- **Session start:** title + reason, with actions *Start*, *Snooze 10 min*, *Skip*.
- **Session end:** actions *Done*, *Need 15 more min*, *Partially done*.
- **Evening wrap-up (configurable, default 9:30 PM):** "Mark what you finished today" — one-tap checklist.
- **At-risk alert:** once a day maximum, only for tasks at risk.
- Quiet hours (default during SLEEP block and during CLASS blocks). Notification channels per type so the user can mute any one.
- Request `POST_NOTIFICATIONS` (Android 13+) and `SCHEDULE_EXACT_ALARM`; degrade gracefully if denied. Reschedule alarms after reboot (`RECEIVE_BOOT_COMPLETED`) and after time-zone change.

### Widgets (Glance)
1. **Now / Next (small, 2x2):** current or next session, time left, tap to mark done.
2. **Today (medium, 4x2):** next 3 items + progress ring for the day.
3. **Deadlines (4x1):** nearest 2 deadlines with risk color.
Widgets update on plan change and every 15 minutes while a session is active.

---

## 11. Backup and restore

- **Export:** full JSON (versioned schema) of all user data via the Storage Access Framework to a user-chosen location (e.g. Google Drive folder via the Files app — chosen by the user, no API).
- **Auto backup:** optional weekly WorkManager job writing to a user-chosen folder (persisted URI permission). Keep the last 4 backups.
- **Restore:** import JSON with a preview ("142 tasks, 18 fixed blocks — replace current data?") and schema version migration.
- Also enable Android Auto Backup (`android:fullBackupContent` / data extraction rules) for the Room DB and DataStore.

---

## 12. Permissions summary

`READ_CALENDAR`, `POST_NOTIFICATIONS`, `SCHEDULE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`, `com.tarun.kaizen.permission.READ_WELLBEING`. **No `INTERNET` permission.**

---

## 13. CI/CD: GitHub Actions builds the APK on every push

Goal: push to GitHub → a signed, installable release APK appears for download.

### Requirements
- Workflow file: `.github/workflows/build.yml`
- Triggers: `push` to `main`, `workflow_dispatch`, and tags `v*`
- Steps: checkout → JDK 17 (Temurin) → Gradle setup with caching → decode keystore from secrets → run unit tests → `assembleRelease` → upload APK as a workflow artifact → create/update a GitHub **Release** with the APK attached
- `versionCode` = `GITHUB_RUN_NUMBER`, `versionName` = `1.0.<run number>` (read from env in `build.gradle.kts`, with local fallbacks)
- **Signing is essential:** Android only installs an update over an existing app if both APKs have the same signature. A fresh debug key per CI run would force uninstalling (and losing data) on each update. So always sign with one fixed release keystore stored in GitHub Secrets.
- Enable R8 minification for release with correct ProGuard rules for Room, Hilt, kotlinx.serialization, and Glance.
- APK filename: `kairos-v<versionName>.apk`

### Secrets the workflow expects
| Secret | Content |
|---|---|
| `KEYSTORE_BASE64` | Base64 of the `.jks` file |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias |
| `KEY_PASSWORD` | Key password |

Also write a `README.md` section explaining how to create the keystore once and add the secrets:

```bash
keytool -genkeypair -v -keystore tarun-release.jks -alias tarun \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -i tarun-release.jks | pbcopy   # macOS: copies the value for KEYSTORE_BASE64
```

**Use this same keystore for the Kaizen app too** (the signature-level permission in Section 9 depends on it). Never commit the keystore to the repo; add `*.jks` to `.gitignore`.

### Reference workflow (adapt as needed, keep the behavior)

```yaml
name: Build APK
on:
  push:
    branches: [main]
    tags: ['v*']
  workflow_dispatch:

permissions:
  contents: write

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - uses: gradle/actions/setup-gradle@v4
      - name: Decode keystore
        run: echo "${{ secrets.KEYSTORE_BASE64 }}" | base64 -d > $RUNNER_TEMP/release.jks
      - name: Unit tests
        run: ./gradlew testReleaseUnitTest
      - name: Build release APK
        env:
          KEYSTORE_PATH: ${{ runner.temp }}/release.jks
          KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
          KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
          KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}
          VERSION_CODE: ${{ github.run_number }}
        run: ./gradlew assembleRelease
      - name: Rename APK
        run: |
          mkdir -p out
          cp app/build/outputs/apk/release/*.apk out/kairos-v1.0.${{ github.run_number }}.apk
      - uses: actions/upload-artifact@v4
        with:
          name: kairos-apk
          path: out/*.apk
      - name: Publish release
        uses: softprops/action-gh-release@v2
        with:
          tag_name: build-${{ github.run_number }}
          name: Kairos build ${{ github.run_number }}
          files: out/*.apk
```

Make sure `gradlew` is committed and executable (`git update-index --chmod=+x gradlew`).

---

## 14. Build order (milestones)

1. **Skeleton:** Gradle setup, version catalog, Hilt, Room, theme, navigation, CI workflow. Verify CI produces a signed APK that installs.
2. **Fixed schedule + tasks:** entities, DAOs, repositories, Fixed schedule screen, Tasks screen, Add task sheet.
3. **Scheduler module:** estimation, free-time builder, energy model, prioritization, placement, reasons, full unit tests.
4. **Today + Week screens:** timeline, Now card, edit/pin/drag, re-plan on edits.
5. **Calendar sync:** permission flow, calendar picker, daily worker, observer, "turn into task".
6. **Kaizen integration:** consumer repository, broadcast receiver, Quick check-in fallback, low-day mode.
7. **Notifications + alarms:** channels, actions, boot/time-zone rescheduling, quiet hours.
8. **Widgets:** three Glance widgets.
9. **Learning + Review:** work logs, estimate correction, energy profile, weekly review screen.
10. **Backup/restore, onboarding, polish:** accessibility pass, empty states, dark mode check, performance check.

---

## 15. Acceptance criteria

- Pushing to `main` produces a downloadable signed APK in GitHub Releases, and installing a newer build updates the app **without** losing data.
- App works with no internet and has no `INTERNET` permission.
- With Kaizen uninstalled, the app still works fully using manual check-in.
- Adding a task with only a name, deadline, and difficulty produces a sensible schedule.
- On a check-in with mood ≤ 2, today's plan visibly lightens and starts with an easy win.
- Skipping a session moves the remaining work forward automatically, without touching pinned sessions.
- Calendar events appear in the timeline and block time.
- Every planned session shows a one-line reason.
- Cold start under 1 second on a mid-range phone; re-planning a week of data under 200 ms.
- All scheduler unit tests pass in CI.
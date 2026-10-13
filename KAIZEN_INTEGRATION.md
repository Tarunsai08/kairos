# Kaizen → Kairos Integration Spec (give this to the AI building Kaizen)

> Kaizen is the user's wellbeing app (mood, sleep, habits, journal). A separate planner app, **Kairos**(`com.tarun.kairos`), needs to read Kaizen's daily check-in data to plan the user's day. Add the provider side described here to Kaizen. Everything stays on-device; no network.

---

## 1. What Kaizen must expose

A **read-only** `ContentProvider` that shares daily check-in data with Kairos only.

| Item             | Value                                                                         |
| ---------------- | ----------------------------------------------------------------------------- |
| Kaizen package   | `com.tarun.kaizen`                                                            |
| Authority        | `com.tarun.kaizen.provider`                                                   |
| Read permission  | `com.tarun.kaizen.permission.READ_WELLBEING`                                  |
| Protection level | `signature` (both apps are signed with the same release keystore)             |
| Write access     | None. `insert`, `update`, `delete` must throw `UnsupportedOperationException` |

### Manifest (Kaizen)
```xml
<permission
    android:name="com.tarun.kaizen.permission.READ_WELLBEING"
    android:protectionLevel="signature" />

<application ...>
    <provider
        android:name=".integration.WellbeingProvider"
        android:authorities="com.tarun.kaizen.provider"
        android:exported="true"
        android:readPermission="com.tarun.kaizen.permission.READ_WELLBEING" />
</application>

```

**Important:** Kaizen and Kairos must be signed with the **same keystore** (the same GitHub Secrets for both repos), or the signature permission will not be granted.

---

## 2. URIs and columns (contract version 1)

### `content://com.tarun.kaizen.provider/meta`

One row: `contractVersion` (INTEGER = 1), `appVersion` (TEXT).

### `content://com.tarun.kaizen.provider/checkins`

One row per date. Supports selection by date, e.g. `selection = "date >= ? AND date <= ?"`, with ISO dates as args. Default order: `date DESC`.

| Column          | Type                       | Meaning                                        |
| --------------- | -------------------------- | ---------------------------------------------- |
| `date`          | TEXT `YYYY-MM-DD`          | The day this check-in describes (local time)   |
| `wake_time`     | TEXT `HH:mm` or NULL       | Time the user woke up that day                 |
| `bedtime`       | TEXT ISO date-time or NULL | When the user went to sleep the previous night |
| `sleep_hours`   | REAL or NULL               | Hours slept the previous night                 |
| `sleep_quality` | INTEGER 1–5 or NULL        | Self-rated sleep quality                       |
| `mood_morning`  | INTEGER 1–5 or NULL        | Morning mood (1 = very low, 5 = great)         |
| `mood_night`    | INTEGER 1–5 or NULL        | Night mood before sleeping                     |
| `energy`        | INTEGER 1–5 or NULL        | Self-rated energy, if Kaizen tracks it         |
| `updated_at`    | INTEGER epoch millis       | Last change to this row                        |

Rules:

- If Kaizen stores mood on a different scale, **normalize to 1–5** in the provider.
- Missing values must be NULL, never 0.
- Add new columns only at the end; never rename or remove columns within a contract version. Bump `contractVersion` for breaking changes.

### Optional (nice to have): `content://com.tarun.kaizen.provider/habits_today`

| Column             | Type                 |
| ------------------ | -------------------- |
| `habit_name`       | TEXT                 |
| `scheduled_time`   | TEXT `HH:mm` or NULL |
| `duration_minutes` | INTEGER or NULL      |
| `done`             | INTEGER 0/1          |

Kairos can treat timed habits (e.g. meditation at 7:00 for 15 min) as busy blocks.

---

## 3. Notify Kairos when a check-in changes

After the user saves the morning or night check-in:
```kotlin
val intent = Intent("com.tarun.kaizen.action.CHECKIN_UPDATED").apply {
    setPackage("com.tarun.kairos")          // explicit, so it works on Android 8+
    putExtra("date", "2026-10-13")
}
context.sendBroadcast(intent, "com.tarun.kaizen.permission.READ_WELLBEING")

```

Also call `contentResolver.notifyChange(checkinsUri, null)` so observers update.

---

## 4. Quality requirements

- The provider must be fast (indexed by `date`) and must never block the UI thread inside Kaizen.
- Use the same Room database Kaizen already uses; expose data through a DAO that returns a `Cursor`, or map results into a `MatrixCursor`.
- Add unit tests for: date filtering, NULL handling, mood normalization, and that write operations throw.
- Add a Settings toggle in Kaizen: "Share mood and sleep with Kairos" (default on). When off, the provider returns an empty cursor.



I have pasted the prompt and the tools need for integration, so start building the app, I want you to give me the best output.
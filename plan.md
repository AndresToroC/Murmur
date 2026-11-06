# Murmur (formerly NocheSonora) — Specification and Implementation Plan

> Android app that records the relevant sounds of the night (snoring, sleep talking, coughing, other sounds), ignores silence and constant background noise, and lets you review and listen to everything the next morning. 100% local, no network.

Plan date: 2026-11-03.

---

## 0. Executive summary

| Aspect | Decision |
|---|---|
| Platform | Android, minSdk 26, targetSdk 36, compileSdk 37 (during implementation: the 2026 libraries require API 37; see README A28) |
| Language / UI | Kotlin 2.4 + Jetpack Compose (Material 3), dark theme by default |
| Architecture | MVVM + a single repository, manual DI with an `AppContainer` |
| Capture | `AudioRecord` 16 kHz mono PCM16, 100 ms blocks (1,600 samples) |
| Detection | 3 layers: adaptive baseline → threshold-based event detector → YAMNet only on candidates |
| Classification | MediaPipe Tasks Audio 1.2.1 + `yamnet.tflite` (float32) in `assets/` |
| Persistence | Room (sessions, events, episodes, pauses) + DataStore (settings) |
| Audio on disk | One WAV per clip in `filesDir/sessions/{sessionId}/{eventId}.wav`; optional full recording in AAC 32 kbps |
| Execution | Foreground Service of type `microphone` + partial wake lock |
| Testing | Detection logic in pure Kotlin with JVM tests and synthetic signals |

---

## 1. Analysis of the requirements document

### 1.1 What is clear and is followed as is
- Scope (Android only, no backend/network/analytics/auth, Spanish-language interface).
- Stack (Compose, Room, Coroutines/Flow, manual DI, `AudioRecord`, MediaPipe + YAMNet).
- The 3 processing layers, the numeric parameters and the label mapping.
- The 5 screens and their content.

### 1.2 Gaps, ambiguities and the decision taken
All of these decisions also go into the README ("Design decisions" section).

| # | Ambiguity / problem | Decision (the simplest one that works) |
|---|---|---|
| A1 | The document ends at "Suggested package structure" with no content. | The structure in section 3 is proposed. |
| A2 | In `AUDIO_CLIPS` mode, MediaPipe splits the clip into 0.975 s windows **with no overlap**. The requirement asks for 50%. | Manual windowing: `classify()` is called with exactly 15,600 samples per window, advancing 7,800 samples (0.4875 s hop). Each call returns a single result. |
| A3 | "Sustained engine" is not defined. | The labels `Engine`, `Idling`, `Engine starting` count as BACKGROUND only if they appear (≥ 0.30) in ≥ 70% of the event's windows; otherwise they count as OTHER. |
| A4 | "Breathing" goes to SNORING "only if periodic". What if it is not? | If the periodicity heuristic is not met, `Breathing` votes as OTHER (the original label is kept). |
| A5 | What if no window exceeds 0.30? | Category OTHER with label `Unknown` ("unknown"). It is not discarded: the event crossed the energy threshold and the user can correct or delete it. |
| A6 | 2 s post-roll vs. 2.5 s hangover. | The "real end" of the event is the last block above the threshold. The clip = [start − 3 s, real end + 2 s]. Since the hangover (2.5 s) is longer than the post-roll, the audio is already in memory when the event closes; there is no need to wait any longer. |
| A7 | Cut at 60 s: does the next event get a pre-roll? | No. The cut event ends at second 60 with no post-roll, and the next one starts at that same instant with no pre-roll (contiguous audio, no duplication). |
| A8 | "Consecutive events of the same category" for episodes. | The session's events are sorted by time; an event joins the previous episode if **the immediately preceding event** has the same effective category and the gap is < 30 s. An event of another category in between breaks the episode. `userCategory ?: category` is used. |
| A9 | How are pauses recorded? | New entity `PauseEntity` (sessionId, startedAt, endedAt, reason). |
| A10 | The service cannot restart itself on Android 14+: a microphone FGS cannot be started from the background. | `START_STICKY` is not used to resume. If the process dies, the session is left INTERRUPTED (see 6.6). |
| A11 | `full.m4a` (MP4) is corrupted if the process dies before the `MediaMuxer` is closed, which is precisely the case worth validating. | Recording uses **AAC ADTS** (`full.aac`), a stream that remains playable even if it is cut off abruptly. ExoPlayer plays it and allows seeking with `FLAG_ENABLE_CONSTANT_BITRATE_SEEKING`. Documented as a deliberate deviation from the requirement. |
| A12 | The Diagnostics "Live" panel with no active session. | Shows the service's data if there is a session. Without a session it shows "No active session" and only "Test for 10 seconds" is available. That button is disabled while a session is running (the microphone is busy). |
| A13 | Warning for < 500 MB free: does it block? | Dialog with "Cancel" / "Start anyway". |
| A14 | When is retention applied? | When the app opens and at the end of each session (no WorkManager). |
| A15 | Audio source: `MIC` applies AGC/noise suppression on many devices, which skews the baseline and "erases" the fan unpredictably. | `UNPROCESSED` if `AudioManager.getProperty(PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)` is `true`; otherwise `VOICE_RECOGNITION` (the CDD requires it to have no AGC). |
| A16 | Battery optimization exemption. | Requested once, after explaining why, with `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (permission `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`). If that fails, `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` is opened. This app is not going to the Play Store, so Play's restriction on that permission does not apply; this is noted in the README. |
| A17 | Manual category correction to BACKGROUND. | The picker only offers SNORING, SPEECH, COUGH, OTHER. To remove something, use "Delete event". |
| A18 | Episode category when the user corrects an event. | After any correction or deletion, the episodes of the whole session are regrouped (a pure, cheap function). |

### 1.3 Main risks
| Risk | Impact | Mitigation |
|---|---|---|
| The manufacturer kills the process (Xiaomi, Samsung, Huawei) | The night is lost | FGS + wake lock + battery exemption + heartbeat in the DB and INTERRUPTED status |
| Microphone AGC/NS | Unstable baseline | `UNPROCESSED`/`VOICE_RECOGNITION` source (A15) |
| YAMNet confuses snoring with engine/breathing | Wrong category | Periodicity heuristic + manual correction + stored top-3 |
| Another app or a call takes the microphone | Silence with no warning (Android 10+ delivers zeros) | `AudioRecordingCallback.isClientSilenced` + check of `AudioManager.mode` + PauseEntity |
| Memory over 8 h | OOM | Only bounded buffers (5 s ring, event ≤ 65 s ≈ 2 MB) and no growing lists |
| Slow inference blocks capture | Lost blocks | Capture never waits: classification runs in another coroutine with a bounded queue |
| Disk | Full in the middle of the night | 500 MB check at start; each clip takes at most ~2 MB |
| Environment: there is currently no JDK or Android SDK on the machine | Cannot build here | Install Android Studio (includes JBR 21) and SDK 36 before building |

---

## 2. Versions (verified on 2026-11-03)

Checked on Google Maven / Maven Central / services.gradle.org:

```toml
[versions]
agp = "9.5.0"
kotlin = "2.4.21"
ksp = "2.3.13"              # KSP2, its own version numbering; check compatibility when syncing
composeBom = "2026.09.00"
activityCompose = "1.13.0"
lifecycle = "2.11.0"          # corrected during implementation (2.10.2 does not exist)
navigationCompose = "2.10.2"  # corrected during implementation (2.11.0 does not exist)
room = "2.8.5"
datastore = "1.3.0"           # check when syncing
coroutines = "1.11.0"
media3 = "1.11.1"
mediapipeTasksAudio = "1.2.1"
junit = "4.13.2"
```

- Gradle wrapper: **9.8.2**.
- YAMNet model (verified URL, HTTP 200, 4,126,810 bytes):
  `https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/1/yamnet.tflite`
  Downloaded only once, when setting up the project, and copied to `app/src/main/assets/yamnet.tflite`; the app never uses the network. `androidResources { noCompress += "tflite" }` is added.
- `abiFilters`: `arm64-v8a`, `armeabi-v7a`, `x86_64` (emulator), to limit the size of MediaPipe's native libraries.

---

## 3. Project and package structure

```
Murmur/
├─ settings.gradle.kts
├─ build.gradle.kts
├─ gradle/libs.versions.toml
├─ gradle/wrapper/…
├─ README.md
├─ plan.md
└─ app/
   ├─ build.gradle.kts
   └─ src/
      ├─ main/
      │  ├─ AndroidManifest.xml
      │  ├─ assets/yamnet.tflite
      │  ├─ res/ (Spanish strings, icons, theme)
      │  └─ java/com/nochesonora/
      │     ├─ MurmurApp.kt            # Application: creates AppContainer, marks orphaned sessions, applies retention
      │     ├─ AppContainer.kt              # manual DI
      │     ├─ MainActivity.kt
      │     │
      │     ├─ core/                        # ⚠ pure Kotlin, no android.* imports
      │     │  ├─ audio/
      │     │  │  ├─ Pcm.kt                 # rms→dBFS, short→float
      │     │  │  ├─ RingBuffer.kt          # ShortArray ring buffer (5 s)
      │     │  │  └─ WavWriter.kt           # writes WAV to an OutputStream
      │     │  ├─ detection/
      │     │  │  ├─ DetectorConfig.kt      # all numeric parameters
      │     │  │  ├─ BaselineTracker.kt     # layer 1
      │     │  │  ├─ EventDetector.kt       # layer 2 (state machine)
      │     │  │  └─ DetectorState.kt       # CALIBRATING / LISTENING / ONSET / EVENT
      │     │  ├─ classification/
      │     │  │  ├─ Category.kt            # SNORING, SPEECH, COUGH, OTHER, BACKGROUND
      │     │  │  ├─ LabelMapper.kt         # YAMNet label → category
      │     │  │  ├─ SnoreHeuristic.kt      # 2–6 s periodicity + energy < 500 Hz
      │     │  │  ├─ VotingClassifier.kt    # weighted voting + top-3
      │     │  │  └─ Windowing.kt           # 15,600-sample windows / 7,800 hop
      │     │  └─ episodes/
      │     │     └─ EpisodeGrouper.kt      # grouping < 30 s
      │     │
      │     ├─ audio/                       # Android: capture and encoding
      │     │  ├─ AudioCapture.kt           # AudioRecord + Flow<ShortArray>, audio source, retries
      │     │  ├─ YamnetClassifier.kt       # MediaPipe AudioClassifier wrapper
      │     │  ├─ AacFullRecorder.kt        # MediaCodec → ADTS
      │     │  └─ InterruptionMonitor.kt    # silenced microphone / call
      │     │
      │     ├─ service/
      │     │  ├─ NightSessionService.kt    # FGS, wake lock, notification
      │     │  ├─ SessionEngine.kt          # orchestrates capture → detector → classifier → repository
      │     │  ├─ LiveState.kt              # StateFlow with level, baseline, state, counters, top-3
      │     │  └─ SessionNotification.kt
      │     │
      │     ├─ data/
      │     │  ├─ db/ (AppDatabase, entities, DAOs, Converters)
      │     │  ├─ settings/SettingsRepository.kt   # DataStore
      │     │  ├─ files/SessionFileStore.kt        # paths, deletion, space used, free space
      │     │  ├─ RetentionPolicy.kt               # pure logic for what to delete
      │     │  └─ NightRepository.kt               # the single repository
      │     │
      │     └─ ui/
      │        ├─ theme/ (Color.kt with per-category colors, Theme.kt, Type.kt)
      │        ├─ navigation/NavGraph.kt
      │        ├─ common/ (ClipPlayer, LevelMeter, CategoryChip, ConfirmDialog)
      │        ├─ home/ (HomeScreen, HomeViewModel)
      │        ├─ history/ (HistoryScreen, HistoryViewModel)
      │        ├─ detail/ (NightDetailScreen, NightDetailViewModel, Timeline)
      │        ├─ settings/ (SettingsScreen, SettingsViewModel)
      │        └─ diagnostics/ (DiagnosticsScreen, DiagnosticsViewModel)
      │
      └─ test/java/com/nochesonora/core/…   # JVM tests for all of `core/`
```

Rule: `core/` must not import `android.*`, `androidx.*` or MediaPipe. That way it can be tested on the JVM.

---

## 4. Audio pipeline in detail

```
AudioRecord (16 kHz, mono, PCM16, blocks of 1,600 samples = 100 ms)
   │
   ├──► RingBuffer (5 s = 80,000 samples) ────────┐ pre-roll
   ├──► AacFullRecorder (if enabled)              │
   ▼                                              │
block dBFS ──► BaselineTracker ──► EventDetector ──┘
                                        │ (event closed: clip PCM)
                                        ▼
                          Channel(capacity 4) ──► classification worker
                                                      │ Windowing → YAMNet → Voting + SnoreHeuristic
                                                      ▼
                                 BACKGROUND: discarded  |  other: WAV + EventEntity + regroup episodes
```

### 4.1 Capture (`AudioCapture`)
- `AudioRecord.getMinBufferSize` × 4 as the internal buffer; blocking reads of 1,600 samples on a dedicated thread (`Dispatchers.IO.limitedParallelism(1)`).
- Audio source as per A15.
- Timing: each continuous capture segment stores an anchor `(wallClockMs, samplesRead = 0)`. A block's time = `anchor + samplesRead / 16`. This avoids the drift of reading the clock on every block. When resuming after a pause, a new anchor is created.
- If `read()` returns an error (`ERROR_DEAD_OBJECT`, `ERROR_INVALID_OPERATION`), the `AudioRecord` is released and recreated with exponential backoff (1, 2, 4… up to 30 s), and a pause is recorded.

### 4.2 Level
- `dBFS = 20·log10(rms / 32768)`, with a floor of −100 dB for all-zero blocks.

### 4.3 Layer 1 — `BaselineTracker`
Parameters in `DetectorConfig`:
- `calibrationMs = 60_000`, `percentileWindowMs = 30_000`, `percentile = 0.20`, `emaTauMs = 120_000`.

Algorithm, for each 100 ms block:
1. **Calibration (first 60 s):** the levels of blocks that are not part of an event are accumulated. At the end, `baseline = p20(levels)` and `variability = p80 − p20` (stored in the session as diagnostic data). **From 5 s on (warm-up) there is a provisional baseline** = p20 of what has been accumulated so far, and the detector already opens events. The UI shows "Listening · calibrating 0:42 / 1:00". *(Change made after the phone test, see §12: with the previous version the detector did not fire for 60 s, and a 1-minute test always gave 0 events.)*
2. **Tracking:** only if the block does **not** belong to an event (state ≠ EVENT and ≠ ONSET) is it added to a sliding window of the last 300 levels (30 s). On each block:
   `target = p20(window)`;
   `baseline += (target − baseline) · (Δt / τ)` with Δt = 100 ms and τ = 120 s (α ≈ 0.00083).
3. Outputs `baselineDb` and `thresholdDb = baselineDb + sensitivity` (14 / 10 / 7 dB).

The percentile makes the baseline robust against spikes; the slow EMA makes a fan that gets switched on become the "new silence" in ~2–4 min. While that fan is raising the baseline, an event may open that the classifier will discard as BACKGROUND; this is expected.

Detail: if an event lasts a long time (for example, continuous snoring), the baseline is not updated during that time; this is fine, because an event lasts 60 s at most and the baseline is only frozen during that stretch.

### 4.4 Layer 2 — `EventDetector` (state machine)
Parameters: `onsetMs = 300`, `hangoverMs = 2_500`, `maxEventMs = 60_000`, `preRollMs = 3_000`, `postRollMs = 2_000`.

```
CALIBRATING ──(end of warm-up, 5 s)──► LISTENING   (calibration continues in the background until 60 s)
LISTENING ──(level > threshold)──► ONSET (counter = 1)
ONSET ──(3 consecutive blocks > threshold)──► EVENT   (start = first block of the onset)
ONSET ──(block ≤ threshold)──► LISTENING
EVENT ──(block > threshold)──► updates lastActive, peak
EVENT ──(25 consecutive blocks ≤ threshold)──► closes: clip = [start − 3 s, lastActive + 2 s]
EVENT ──(duration ≥ 60 s)──► closes with no post-roll and opens another EVENT at the same instant
```

- Input: `onBlock(timestampMs, samples, levelDb)`. Output: `DetectorOutput` (state + `ClosedEvent?` with PCM, start, end, peak dB).
- The event's PCM is accumulated in a `ShortArray` with bounded growth (maximum (3 + 60 + 2.5) s × 16,000 ≈ 1.06 M samples ≈ 2.1 MB).
- The pre-roll is copied from the `RingBuffer` when entering EVENT (the 3 s before the start of the onset).
- On close, the leftover hangover PCM beyond `lastActive + 2 s` is trimmed.
- A pause (microphone lost) immediately closes the open event with whatever it has.

### 4.5 Layer 3 — Classification
**`YamnetClassifier` (Android):**
- `AudioClassifier.createFromOptions(context, AudioClassifierOptions.builder().setBaseOptions(BaseOptions.builder().setModelAssetPath("yamnet.tflite").build()).setRunningMode(RunningMode.AUDIO_CLIPS).setMaxResults(10).setScoreThreshold(0.05f).build())`.
- Per window: `AudioData.create(AudioDataFormat.builder().setNumOfChannels(1).setSampleRate(16000f).build(), 15600)`, `load(FloatArray)` (PCM16 / 32768) and `classify(audioData)`.
- A single instance, created when the session starts and closed at the end. Runs on a single-threaded worker.

**`Windowing` (pure):** windows of 15,600 samples with a hop of 7,800. If the clip is shorter than one window, or a tail is left over, it is zero-padded (YAMNet tolerates this). A 65 s clip yields ~132 windows; on an average phone that is between 1 and 3 s of inference, which runs off the capture thread.

**`LabelMapper` (pure):** exact match against the name MediaPipe returns (`category.categoryName()`):

| Category | Labels |
|---|---|
| SNORING | `Snoring`, `Gasp`, `Breathing` (only if periodic, A4) |
| SPEECH | `Speech`, `Conversation`, `Whispering`, `Babbling`, `Narration, monologue`, `Shout`, `Laughter` |
| COUGH | `Cough`, `Sneeze`, `Throat clearing` |
| BACKGROUND | `Mechanical fan`, `Air conditioning`, `Hum`, `White noise`, `Pink noise`, `Silence`, `Static`, sustained engine (A3) |
| OTHER | any other label ≥ 0.30 (the original name is kept) |

**`SnoreHeuristic` (pure)** on the event's PCM:
1. Energy envelope in 100 ms blocks; 3-block smoothing.
2. Peaks: local maxima ≥ median + 6 dB, with a minimum separation of 1.5 s.
3. Periodic if there are ≥ 3 intervals between peaks, all within [2 s, 6 s], with a coefficient of variation < 0.35.
4. Low frequency: energy < 500 Hz / total energy ≥ 0.6. Computed with a 2nd-order IIR low-pass filter (biquad, fc = 500 Hz) instead of an FFT, to keep it simple.
5. `isSnoreLike = periodic && lowFrequency`. `isPeriodic` is also exposed (used for `Breathing`).

**`VotingClassifier` (pure):**
1. Per window, each label with score ≥ 0.30 adds its score to its category (`Breathing` goes to SNORING only if `isPeriodic`; the engine labels are resolved with A3 after counting windows).
2. If `isSnoreLike`: `score[SNORING] = score[SNORING] · 1.5 + 0.3 · nWindows`. This reinforces snoring even if YAMNet did not say "Snoring".
3. Final category = argmax. No votes → OTHER / `Unknown` (A5).
4. `confidence = score[final] / nWindows`, clamped to [0, 1].
5. `topLabels` = the 3 labels with the highest mean score across windows → JSON `[{"label":"Snoring","score":0.62},…]`.
6. For OTHER, `otherLabel` = the OTHER label with the most weight.
7. **Background never beats a real sound:** if SNORING, SPEECH or COUGH have any vote, the BACKGROUND score is zeroed. BACKGROUND can only beat OTHER. *(Change made after the phone test, see §12.)*
8. If the final category is BACKGROUND → it is discarded (no WAV is written, nothing goes into the DB; in Diagnostics and on Home it shows as "Ignored").

### 4.6 Episodes — `EpisodeGrouper` (pure)
`fun group(events: List<EventLite>): List<EpisodeLite>`: sorts by `startedAt` and joins an event to the current episode if the effective category is the same and `event.startedAt − episode.endedAt < 30 s`. In the repository, after inserting, correcting or deleting an event, the episodes of that session are recomputed inside a transaction (delete episodes + insert + update each event's `episodeId`). That is a few hundred rows at most, so the cost is negligible.

### 4.7 Full recording (optional) — `AacFullRecorder`
- `MediaCodec` AAC-LC, 16 kHz, mono, 32 kbps. Fed with the same PCM blocks (no second `AudioRecord` is opened).
- Each output frame is written to `full.aac` with a 7-byte ADTS header (A11). Flushed every 10 s.
- During pauses there is no audio, so an event's real time does not match its offset in the file. The **file offset in ms** at the start of each capture segment is stored: `PauseEntity` holds it, and to seek to an event the offset is computed as `offset = eventTime − sessionStart − Σ duration of the previous pauses`.
- Estimated size: 32 kbps × 8 h ≈ 115 MB (shown in the dialog before enabling it).

---

## 5. Data model (Room, version 1, `exportSchema = true`)

```kotlin
enum class SessionStatus { IN_PROGRESS, COMPLETED, INTERRUPTED }
enum class Category { SNORING, SPEECH, COUGH, OTHER, BACKGROUND }   // BACKGROUND is never persisted

@Entity("sessions")
data class SessionEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val startedAt: Long,
  val endedAt: Long?,                 // null while IN_PROGRESS
  val status: SessionStatus,
  val baselineDbAtStart: Float?,      // filled in when calibration ends
  val lastHeartbeatAt: Long,          // heartbeat every 30 s (for INTERRUPTED)
  val sensitivityDb: Int,             // threshold used that night
  val fullRecordingPath: String?,
  val isFavorite: Boolean = false,
  val notes: String?
)

@Entity("events", foreignKeys = [FK(sessions, CASCADE)], indices = [sessionId, episodeId])
data class EventEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val sessionId: Long,
  val episodeId: Long?,
  val category: Category,
  val otherLabel: String?,            // original YAMNet label when OTHER
  val startedAt: Long, val endedAt: Long,
  val peakDb: Float,
  val confidence: Float,
  val topLabelsJson: String,
  val filePath: String,
  val userCategory: Category?
)

@Entity("episodes", foreignKeys = [FK(sessions, CASCADE)], indices = [sessionId])
data class EpisodeEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val sessionId: Long, val category: Category,
  val startedAt: Long, val endedAt: Long, val eventCount: Int
)

@Entity("pauses", foreignKeys = [FK(sessions, CASCADE)], indices = [sessionId])
data class PauseEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val sessionId: Long, val startedAt: Long, val endedAt: Long?,
  val reason: String                 // stored values: "MIC_BUSY", "CALL", "AUDIO_ERROR", "USER"
)
```

Changes from the requirements document: `lastHeartbeatAt`, `sensitivityDb`, `isFavorite`, `otherLabel` and the `pauses` table. All of them are needed to meet other requirements (interrupted session, favorites, OTHER with its original label, pause log).

**Key queries:** `observeSessionsWithSummary()` (Flow with duration and counts per effective category: snoring minutes = sum of the durations of the SNORING episodes), `observeSessionDetail(id)`, `findOrphanSessions()` (IN_PROGRESS, i.e. in progress).

**Settings (DataStore):** `sensitivity` (LOW/MEDIUM/HIGH, i.e. low/medium/high; default MEDIUM), `saveFullRecording` (false), `retention` (FOREVER/DAYS_7/DAYS_30, i.e. always/7 days/30 days; default FOREVER), `batteryPromptShown` (false).

**Files (`SessionFileStore`):** `filesDir/sessions/{sessionId}/{eventId}.wav`. The event is inserted first to obtain its `id`; then the WAV is written to `.tmp` and renamed (atomic write); then `filePath` is updated. If the write fails, the row is deleted. Deleting a session deletes the whole folder and the row (cascade).

**Retention (`RetentionPolicy`, pure):** given `now`, the policy and the list `(id, endedAt, isFavorite, status)`, it returns the ids to delete. It never deletes favorites or the IN_PROGRESS session.

---

## 6. Foreground Service — `NightSessionService`

### 6.1 Manifest
```xml
<uses-permission android:name="android.permission.RECORD_AUDIO"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.WAKE_LOCK"/>
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"/>

<service android:name=".service.NightSessionService"
         android:exported="false"
         android:foregroundServiceType="microphone"/>
```
No `INTERNET` permission: a verifiable guarantee that nothing leaves the phone.

### 6.2 Start (always from the foreground UI)
1. Permissions: `RECORD_AUDIO` (required) and `POST_NOTIFICATIONS` on 33+ (if denied, the session still works; the user is warned).
2. First time: dialog explaining battery optimization → A16. `batteryPromptShown` is saved.
3. Free space ≥ 500 MB (`StatFs(filesDir)`), otherwise → A13.
4. `ContextCompat.startForegroundService()`; in `onStartCommand`: `ServiceCompat.startForeground(id, notif, FOREGROUND_SERVICE_TYPE_MICROPHONE)` **before** anything else.
5. Creates the `SessionEntity` (IN_PROGRESS), acquires `PARTIAL_WAKE_LOCK` (`"Murmur:session"`, no timeout, released in `finally`) and starts `SessionEngine` in a `CoroutineScope(SupervisorJob() + Dispatchers.Default)`.

### 6.3 Notification
- Channel "Night session", importance LOW (no sound).
- Title "Listening to the night", `setUsesChronometer(true)` + `setWhen(startedAt)` for the elapsed time without redrawing every second.
- Text "N events detected", updated only when the counter changes.
- "Stop" action → `PendingIntent` to the service with `ACTION_STOP`. Tap → opens `MainActivity`.
- `setOngoing(true)` and `setForegroundServiceBehavior(FOREGROUND_SERVICE_IMMEDIATE)`.

### 6.4 Stopping
`ACTION_STOP` or the button on Home → closes the open event (it is classified and saved), drains the classification queue (waiting at most 10 s), closes the AAC, marks the session COMPLETED with `endedAt`, releases AudioRecord, MediaPipe and the wake lock, applies retention, `stopForeground` + `stopSelf`.

### 6.5 Interruptions (`InterruptionMonitor`)
- API 29+: `AudioManager.registerAudioRecordingCallback`; if our session's configuration has `isClientSilenced == true` → pause `MIC_BUSY`; when it goes back to `false` → resume.
- Calls: poll `AudioManager.mode` every 2 s (`MODE_IN_CALL`/`MODE_IN_COMMUNICATION` → pause `CALL`). Does not require `READ_PHONE_STATE`.
- `read()` errors → pause `AUDIO_ERROR` + retry (4.1).
- API 26–28: only the error path + mode polling (the callback does not exist).
- On pause: close the open event, insert a `PauseEntity` and show "Paused (microphone busy)" in the UI and the notification. On resume: close the pause, create a new time anchor, and **do not** recalibrate (the baseline is kept).

### 6.6 Process death
- Heartbeat: `lastHeartbeatAt = now` every 30 s and on every saved event.
- In `MurmurApp.onCreate` (and before starting a session): every IN_PROGRESS session whose service is not alive (`LiveState.activeSessionId != id`) becomes INTERRUPTED with `endedAt = lastHeartbeatAt`. Clips already written are kept. With ADTS, `full.aac` remains playable.
- `onStartCommand` returns `START_NOT_STICKY` (A10). `onTaskRemoved` does not stop the session (swiping the app away in "Recents" must not kill it).

### 6.7 Live state
`LiveState` is a singleton in `AppContainer` (same process, no binding). It exposes a `StateFlow<LiveSnapshot>` with `sessionId, startedAt, phase (CALIBRATING/LISTENING/EVENT/PAUSED), levelDb, baselineDb, thresholdDb, calibrationProgress, countsByCategory, lastTop3`. The level is published at 10 Hz at most; the UI uses `collectAsStateWithLifecycle`, so with the screen off it costs nothing.

---

## 7. Screens (Compose, Navigation Compose, bottom bar Home / History / Settings)

Per-category colors (on the dark theme, AA contrast): SNORING amber `#FFB74D`, SPEECH blue `#64B5F6`, COUGH red `#E57373`, OTHER gray `#B0BEC5`. Background `#0E1116`. Dark theme always on by default (it does not follow the system, so it does not dazzle at night).

### 7.1 Home
- No session: large round "Start night" button; card "🔌 Keep the phone plugged into the charger"; link to the last night.
- With a session: "Stop" button (with confirmation); stopwatch; status ("Measuring the silence…" for the first 5 s, "Listening · calibrating 0:42 / 1:00", "Listening", "Sound in progress", "Paused"); horizontal level meter with the baseline and the threshold marked; "Baseline: −52 dBFS · Threshold: −42 dBFS"; **"Background detected: Fan · ignored"** (YAMNet on 5 s of event-free audio, at 5 s and when calibration ends); four chips with per-category counters; **"Recently heard"** card with the 6 most recent clips (time + category, or "Ignored · background (…)") and the total ignored as background.
- Keep the screen on: **no**; it can be turned off.

### 7.2 History
- `LazyColumn` of cards: date ("Mon, Nov 2 · 23:14 → 06:51"), duration, status badge, summary ("Snoring 42 min · Talked 3 times · Cough 5 · Other 7").
- Star to mark as favorite; delete from a menu with a confirmation `AlertDialog`.
- Empty state: "No nights recorded yet".

### 7.3 Night detail
- **Timeline** (`Canvas`): axis from the start time to the end time, colored marks per episode (proportional width, minimum 3 dp), gray bands for pauses, hour ticks every hour. Tapping a mark scrolls to the episode and expands it.
- **Numeric summary**: the same data as in History, plus the initial baseline and the sensitivity used.
- **Episodes**: expandable card (category, time, duration, number of clips). When expanded, it shows the events: time, duration, peak dB, confidence, top-3 (small text), player, "Change category" menu (SNORING/SPEECH/COUGH/OTHER) and "Delete" (with confirmation).
- **Player**: a single `ExoPlayer` in the ViewModel (released in `onCleared`); the active clip shows play/pause and a progress `Slider`. Playing another clip stops the previous one.
- **Full recording** (if it exists): player at the top; each event has "Go to this time in the full recording" (offset as per 4.7).

### 7.4 Settings
- Sensitivity: segmented button Low / Medium / High (with "14 / 10 / 7 dB" below). Applies to the next session.
- "Save the full night recording": switch; when turned on, a dialog says "Takes ~115 MB per 8 hours".
- Retention: Forever / 7 days / 30 days, with the note "Favorites are never deleted".
- Storage: "The app uses 312 MB · Free on the phone 21 GB"; "Delete everything" button (double confirmation; disabled during a session).
- Entry to "Diagnostics".
- App version and "Everything is processed on the phone. Nothing leaves the device."

### 7.5 Diagnostics
- Live (if there is a session): dBFS level, baseline, threshold, detector state, top-3 of the last classified window (includes the events discarded as BACKGROUND, marked "discarded").
- "Test for 10 seconds" (without a session): requests the permission if needed, records 10 s in the ViewModel (`AudioCapture` directly, in the foreground), classifies with 50%-overlapping windows, and shows the mean level, the resulting category, `isSnoreLike` and the list of labels with their mean score. Saves nothing.

---

## 8. Testing strategy

### 8.1 JVM tests (`app/src/test`, JUnit 4), with a synthetic signal generator in `test/…/SignalGen.kt`
| Class | Cases |
|---|---|
| `Pcm` | zeros → −100 dB; full-scale sine → ≈ −3 dBFS |
| `RingBuffer` | write with wrap-around, read of the last N ms |
| `WavWriter` | correct 44-byte header and sizes |
| `BaselineTracker` | calibrates to p20; a 1 s spike does not move the baseline; a sustained +15 dB step is absorbed in ~4 min; event blocks do not update it |
| `EventDetector` | 200 ms of noise does not trigger; 300 ms does; a 2.4 s hangover keeps a single event and 2.6 s splits it in two; cut at 60 s; exact 3 s pre-roll and 2 s post-roll; a pause closes the event |
| `Windowing` | number of windows, overlap and padding |
| `LabelMapper` / `VotingClassifier` | mapping of all labels; weighted voting; 0.30 threshold; sustained vs. one-off engine; no votes → OTHER/Unknown; BACKGROUND; top-3 |
| `SnoreHeuristic` | noise pulses filtered at 150 Hz every 3.5 s → snore-like; aperiodic synthetic voice → not; pulses every 1 s → not |
| `EpisodeGrouper` | gaps of 29 s / 31 s; an interleaved category breaks the episode; `userCategory` takes precedence |
| `RetentionPolicy` | 7/30 days, favorites, IN_PROGRESS |

### 8.2 Instrumented tests (minimal)
- Room DAO: cascade on session deletion, per-category summary.
- `YamnetClassifier`: loading the model from assets and classifying 1 s of zeros (no crash, returns results).

### 8.3 Manual MVP validation (README checklist)
1. Diagnostics → "Test for 10 s" while talking, coughing, and with the fan on.
2. 15-minute session with the fan on the whole time → 0 BACKGROUND events saved.
3. Turn the fan on in the middle of the session → the baseline rises and there is no avalanche of events.
4. During the session, open the voice recorder or receive a call → pause recorded and automatic resume.
5. Force stop from the system Settings → on reopening, the session shows as INTERRUPTED with the time of the last heartbeat.
6. A full real 8-hour night with "Save the full night recording": battery consumed, stable memory (no growth) and comparison of the detected events against the recording.
7. Test on at least one Samsung or Xiaomi with the default battery optimization.

---

## 9. Implementation phases (recommended order)

Each phase leaves the app compiling.

> Status (2026-11-03): code for F0–F8 written. `assembleDebug`, `test` (71 JVM tests green), `compileDebugAndroidTestKotlin` and `lintDebug` pass (built with JDK 21 + the Android SDK inside Docker). It still has to be run on a phone: instrumented tests and the §8.3 checklist. See the README, "Status" section.

- [x] **F0 — Skeleton**: Gradle project (KTS, version catalog, wrapper 9.8.2), `app` with Compose + dark theme + navigation with 3 empty tabs, `AppContainer`, `yamnet.tflite` in assets, initial README.
- [x] **F1 — Pure core + tests**: `core/audio`, `core/detection`, `core/classification`, `core/episodes`, `RetentionPolicy`, with all the tests in 8.1 green.
- [x] **F2 — Data**: entities, DAOs, `AppDatabase`, `SettingsRepository`, `SessionFileStore`, `NightRepository` (includes episode regrouping and session deletion with its files).
- [x] **F3 — Android audio**: `AudioCapture`, `YamnetClassifier`, `InterruptionMonitor`, `AacFullRecorder`.
- [x] **F4 — Service**: `NightSessionService`, `SessionEngine`, `LiveState`, notification, wake lock, heartbeat, orphaned sessions.
- [x] **F5 — Home + permissions**: full start and stop flow, battery, free space, live UI.
- [x] **F6 — History + Detail**: list, favorites, delete, timeline, episodes, player, category correction, full recording.
- [x] **F7 — Settings + Diagnostics**: all options, storage, delete everything, 10 s test.
- [x] **F8 — Hardening**: automatic retention, leak review (wake lock, AudioRecord, ExoPlayer, MediaPipe), final README with decisions and validation checklist.

---

## 10. MVP acceptance criteria

1. An 8-hour session with the charger connected ends COMPLETED, with no crash and with stable memory.
2. With a constant fan, the app does not save fan events.
3. Continuous snoring shows up as one or a few episodes, not as hundreds of rows.
4. Every event has a playable WAV with 3 s of pre-roll and 2 s of post-roll.
5. If the process is killed, the session is left INTERRUPTED with the time of the last data.
6. A call or a busy microphone produces a visible pause, and capture resumes by itself.
7. Deleting a session removes its files; retention respects favorites.
8. The APK does not declare `INTERNET`.
9. `./gradlew test` is green.

---

## 11. Out of scope (explicitly)
iOS, backend, sync, accounts, exporting or sharing clips, widgets, multi-night statistics, smart alarm, training custom models, languages other than Spanish.

---

## 12. Phase 2 — Phone validation and next steps

### 12.1 Log of the first test on a phone (2026-11-03)
| # | What was seen | Cause | Fix | Status |
|---|---|---|---|---|
| B1 | ~1 min session talking and coughing: "0 events". | The 60 s calibration blocked detection: the whole minute fell inside it. | Provisional baseline from 5 s on; event blocks do not feed the calibration (§4.3). | Fixed, with tests `detectsEventsWhileStillCalibrating` and `SessionSimulationTest` |
| B2 | Coughs and snores would have been deleted anyway after the first minute. | Voting: "Silence"/fan appears in every window of the clip (pre/post-roll) and the cough in only 1–2; summing over windows, BACKGROUND won and the event was discarded. Verified with real YAMNet (same `.tflite`) on ESC-50 clips: **6 of 6** coughs/snores in a quiet room came out as BACKGROUND. | Background never beats SNORING/SPEECH/COUGH (§4.5 step 7). With the new rule, 15 of 15 clips come out right. | Fixed, with tests `shortCoughAmongSilenceIsCough`, `speechOverFanIsSpeech` |
| B3 | During the night there is no way to see what the app is hearing (Diagnostics did show it). | Home only showed counters. | "Background detected" + "Recently heard" + total ignored (§7.1). | Done |
| **B4** | **2 min session (past calibration): still shows nothing. Main cause of the "0 events".** | `SessionFileStore.writeWav` closed the file and **then** called `fd.sync()`, which always throws `SyncFailedException` on a closed file. The repository treated this as a write failure, deleted the row and carried on **without any warning**: no event was ever saved. Reproduced on the JVM with the same call order. | Correct order flush → sync → close in `WavWriter.writeFileAtomic` (pure core, with test `atomicWriteSucceedsAndLeavesNoTmp`). In addition, every save failure is now shown on Home in red ("NOT SAVED" + error counter) instead of getting lost in the log. | Fixed |
| **B5** | **3 min test (14:21–14:25) after "fixing" B1–B4: "No events · 0 clips"; baseline −47 dBFS.** | Each build ran in a new container, and the debug signing key (`~/.android/debug.keystore`) was not persisted: **each APK was signed with a different key**. Android does not install an update signed with a different key over the existing app, so most likely the phone was still running the **first** version, which had B1–B4. On top of that, they all said "0.1.0": there was no way to tell which one was installed. | A fixed debug key kept inside the project (`app/debug.keystore`, standard credentials `android`/`androiddebugkey`). Version **0.2.0** (versionCode 2) visible on Home and in Settings; bumped with every APK delivered. **The app has to be uninstalled once** to switch to the new key. | Fixed |
| B6 | There was no way to test the full session without a phone. | `SessionEngine` created the microphone and YAMNet internally. | They are now injected (`AudioSource`, `WindowClassifier`), and `SessionEngineTest` (Robolectric) runs the real session — engine → detector → classifier → Room → WAV — with recorded audio: 2.5 min at −47 dBFS with talking during calibration, talking, coughing and snoring ⇒ 5 events with their WAVs, 0 errors. Runs in the build container (Docker, max. 3 GB / 2 CPU). | Done |
| B7 | The first run of `SessionEngineTest` saved 4 of 5 sounds. | The classification queue had capacity 4 and dropped events (`trySend`) when full: a burst of events (consecutive snores, long clips) could lose events. | Capacity 16 (≈ 32 MB in the worst case). The test now saves 5 of 5. | Fixed |
| — | Diagnostics "Test for 10 s" correctly recognizes cough, snoring, talking and fan. | — | Confirms that capture + YAMNet work on the phone. | OK |

### 12.2 How to test on the phone now (short test, 3 min)
1. **This time, uninstall NocheSonora first** (long-press the icon → Uninstall) and then install the new APK. Open the app: at the bottom of Home it should say **"Version 0.2.0"**. If it says anything else, the new APK was not installed. From now on, updates install over the existing app without uninstalling.
2. Fan on. Tap "Start night" and leave the phone still for 10 s: "Background detected: …" should appear.
3. Talk for 3–4 s, wait 5 s, cough 2–3 times, wait 5 s, snore/imitate snoring for 10 s.
4. Each sound should appear in "Recently heard" about 3–5 s after it ends (the hangover is 2.5 s), and add to the counters. If anything appears in red ("NOT SAVED" or "Errors"), write down the exact text.
5. Stop and open the night in History: the clips should be there and playable.

### 12.3 How it is built and validated (without installing anything on the machine)
Everything runs in Docker containers that are removed when they finish (`--rm`), with bounded resources:
- **Build + tests** (`eclipse-temurin:21-jdk`, max. **3 GB RAM / 2 CPU**): `testDebugUnitTest assembleDebug`. Persistent volumes: Android SDK, Gradle cache, Robolectric's Maven cache and the working copy (`nochesonora-android-sdk`, `nochesonora-gradle-home`, `nochesonora-m2`, `nochesonora-work`). Robolectric downloads its `android-all` (~150 MB) from Maven Central; if it responds with 429 (download limit), use Google's mirror with `-e JAVA_TOOL_OPTIONS=-Drobolectric.dependency.repo.url=https://maven-central.storage-download.googleapis.com/maven2`.
- **Screenshots without a phone:** `-e NS_SCREENSHOTS=1` and `testDebugUnitTest --tests com.nochesonora.ui.ScreenshotTest` → PNGs in `app/build/screenshots` (Home live and idle, night detail, history, meter and waveform). Limitation: they capture the first frame, so the live meter and the clip waveform (which fill in later) are checked separately in `5_componentes.png`.
- **If a test on the phone fails again with no explanation:** a minimal container with `adb` (Alpine + android-tools, ~15 MB, 256 MB RAM) connected to the phone over **wireless debugging** (Developer options → Wireless debugging → Pair device with pairing code). This makes it possible to read the app's real log (`adb logcat`) and install the APK directly, with no cable and without installing anything on Windows.

### 12.4 Next steps (in order)
- [x] **P1 — Repeat the test with the fixed APK (0.2.0).** First real night on 2026-11-03: 23:31 → 03:04 (3 h 33 min, stopped manually), status COMPLETED (no process death), baseline −52 dBFS, sensitivity 10 dB, **17 clips**: Talked 8 times, Other 9, no snoring or coughing. Most of the events between 23:31 and 23:41 (while going to bed); after that, isolated ones around 00:00, 01:15, 02:00, 02:25 and 02:40. **Pending: find out whether anything was missed** (P2). Detail noticed: short episodes are shown as "0 s"; they should say "< 1 s".
- [ ] **P2 — 1-hour test** with the fan on and full recording enabled: compare the events against the full audio (was anything missed? how many "Ignored" were there, and were they really background?).
- [ ] **P3 — Tune sensitivity with data**: if soft sounds are missed (whispered talking), try High (7 dB); if there is too much OTHER, check which YAMNet labels cause it (top-3 stored on every event).
- [ ] **P4 — Full night (8 h)** on the charger: battery, stable memory, no process death. Repeat on a Samsung or Xiaomi.
- [ ] **P5 — Instrumented tests** (`connectedAndroidTest`) with the phone connected over USB.
- [ ] **P6 — Lighter APK** (optional): it currently weighs ~125 MB because it includes MediaPipe for 3 architectures; a release APK with only `arm64-v8a` would be much smaller.
- [x] **P7 — New design (0.3.0).** Direction A of the prototype was chosen (§12.5). Still needs to be checked on the phone.

### 12.5 "Murmur · Instrument" design (0.3.0, 2026-11-04)
A prototype of 3 directions in Claude (A Murmur, B Hush, C Nocturne); **A** was chosen. Only the interface changes: detection, classification and data stay the same.
- **Theme** (`ui/theme`): background `#0A0F1C`, surfaces `#111829` with border `#1E2940`, accent `#7CC4FF`; categories: snoring `#F2B44B`, talking `#7CC4FF`, cough `#FF8A80`, other `#9AA6B8`. Text in Inter Tight; times, dB values and figures in JetBrains Mono (variable fonts in `res/font`, OFL license, no network).
- **Home, live:** large session clock `01:12:40`, "Listening · since 23:31", "Live level" card with a bar meter (bars above the threshold in blue, dotted threshold) and `base / threshold / bg`, 4 per-category tiles, "Recently heard" with times in mono, Stop button at the bottom.
- **Home, idle:** current time, "Before you sleep" tips, last-night card with tiles, "Start night" button.
- **Night detail:** square back/favorite (star) buttons, tiles (duration + the categories that have something), timeline with one bar per episode and the hours below, episodes in cards; when an episode is opened: player with the **real WAV waveform** (tap to seek), peak/confidence/top-3, "Change category" and "Delete".
- **History, Settings, Diagnostics and the bottom bar** in the same style.
- Short episodes now say "< 1 s" (previously "0 s").
- The app name became Murmur in 0.3.1 (see §12.6).

### 12.6 Rename to Murmur (0.3.1, 2026-11-04)

- Visible: `app_name` (icon and notification), Home header, version line in Settings and the text asking to ignore battery optimization.
- Code: `NocheSonoraApp` → `MurmurApp`, `NocheSonoraTheme` → `MurmurTheme`, `Theme.NocheSonora` → `Theme.Murmur`, wake lock tag `Murmur:session`, thread names, `rootProject.name`.
- Project folder: `Desktop/Personal/Murmur` (previously `Desktop/Personal/NocheSonora`). In the Docker commands, the `/src` volume must point to the new folder. APK: `Murmur-debug.apk`.
- **Kept on purpose:** the `applicationId` and the Kotlin package `com.nochesonora`, and the `nochesonora.db` database. Changing the `applicationId` would make Android treat it as a different app: it would not install over the existing one, and the recorded nights would stay in the old app. The Docker volumes (`nochesonora-*`) also keep their names so the SDK and caches do not have to be downloaded again.

### 12.7 Calendar, visible calibration and language (0.4.0, 2026-11-04)

- **History = calendar.** A month view with arrows (from the month of the oldest night to the current month). Each day with recordings carries up to 3 dots in the colors of what was heard (snoring, talking, cough, other; one blue dot if the night had no events). Tapping a day lists its nights below; on opening, the day of the most recent night is selected. A night belongs to the day it **started** (23:31 on the 3rd → day 3). Pure logic in `ui/history/CalendarModel.kt` with `CalendarModelTest`. Weeks start on Monday in Spanish and on Sunday in English.
- **Calibration minute.** While `calibrationProgress < 1`: a "◌ MEASURING" pill, the status "Measuring the room" and a card with a progress bar (0:23 / 1:00, "37 s left"), what is happening and the next step. When it finishes, a 12 s "Room measured" notice with the detected background and the baseline; then "● RECORDING". Detection did not change: from 5 s on there is already a provisional threshold, and anything that sounds during that minute is recorded.
- **"Last night" on Home** is the most recent finished session (completed or interrupted); the one in progress does not count. It is the same as the first row in History.
- **Language.** Settings → Language: Español / English (Spanish by default). Strings in `res/values*/strings.xml` and `strings_screens.xml`. Stored in SharedPreferences (`AppLanguage`) because it has to be read synchronously in `attachBaseContext` of the activity, the `Application` and the service; changing it recreates the activity. Dates follow the language ("Mar 3 nov" / "Tue, Nov 3"). In English, YAMNet labels are shown as they are. If the language is changed during a session, the notification changes on the next night. The engine's technical error messages remain in Spanish.

### 12.8 The night outside the app, trends, transcription and more sounds (0.5.0, 2026-11-04)

- **Pause / resume.** From the notification (visible with the phone locked: channel `night_session_v2` with DEFAULT importance and no sound, `VISIBILITY_PUBLIC`; the old channel is deleted) and from Home (Pause / Resume next to Stop). A manual pause (`PauseReason.USER`) closes the open event, **releases the microphone** (Android's green indicator turns off) and is recorded as a pause on the timeline; on resume, the microphone is reopened and recording continues with the same baseline, without recalibrating. Test `userPauseReleasesTheMicrophoneAndResumes`.
- **Morning summary.** When the session stops, a "Your night · Tue, Nov 3" notification with the duration, times talked, snoring, coughing and other sounds; tapping it opens that night. Its own silent channel. Can be turned off in Settings. Test `NightSummaryTest`.
- **Quick Settings tile** (`MurmurTileService`). Without a session: opens the app and starts the night through the same path as the Home button (permissions, battery, space); that way Android allows the microphone to start, since it cannot be started from the background. With a session: stops it. Shows "Recording" / "Paused".
- **Widget** (`MurmurWidget`, RemoteViews with no new dependencies). Idle: the last night and "Start night". Recording: stopwatch and "Stop". It is refreshed by the service (start, pause, resume, finish) and by the app when it opens; it does not update on a timer.
- **Trends** (History → Trends): 7 or 30 days; nights, snoring and talking per night, and total coughs, compared against the previous period; daily charts; highlighted nights. Pure logic in `TrendsModel` with tests.
- **Transcription** (Android 13+): only the **on-device** recognizer (`createOnDeviceSpeechRecognizer`), reading the clip's WAV; never the network one, and the app still has no `INTERNET`. If the language is missing, the detail screen offers "Download language to transcribe" (the system downloads it). Talking clips are transcribed automatically when the night is opened (can be turned off in Settings). `events.transcript`: null = not attempted, "" = not understood. **Database v2** with migration `MIGRATION_1_2` (no nights are lost).
- **More sounds.** "Other" events show one of 28 types (dog, cat, baby, door, footsteps, alarm, traffic, rain, music/TV, sneeze, room noise…), derived from 503 of YAMNet's 521 labels. Only the display changes: classification into SNORING / SPEECH / COUGH / OTHER is the same. "Sounds identified" section in the detail screen.

**Still to be checked on the phone:** the lock-screen notification and its buttons; that pausing turns off the microphone indicator; the summary on stop; adding the Quick Settings tile and the widget; transcription (depends on the phone having on-device recognition for Spanish).

### 12.9 "Luna de barras" (bar moon) logo (0.5.1, 2026-11-04)

- Chosen from the 7 proposals in `design/logo/murmur-logo.html`. A crescent moon cut into vertical bars: it says night and sound at the same time, without a microphone or stars, and follows the app's bar language.
- **Refined from the proposal:** with 6 bars and the cut at the top right, it read as a descending bar chart. It now has 9 thinner bars, and the cut is that of a classic moon (a "C" open to the right), so both tips are visible. Chosen from 4 rendered variants.
- **Where it is used:** adaptive icon (`ic_launcher_foreground` on a night-blue background with a soft glow, inside the 66 dp safe zone) with its own monochrome layer for Android 13+ themed icons (`ic_launcher_monochrome`); a small 5-bar version with wider gaps for the notification (`ic_stat_moon`), the Quick Settings tile and the widget (`ic_tile`), and the app header (`BarsIcon`).
- The geometries come from cutting vertical bars with two circles (moon = C1 − C2); screenshot `13_logo` in `ScreenshotTest`.

### 12.10 More reliable transcription (0.5.2, 2026-11-04)

- **Problem on the phone:** clips in which what was said can be heard clearly came out as "No words could be made out" or with only a fragment of the text.
- **Causes:** (1) sleep speech is quiet and the recognizer discarded it; (2) the clip starts with ~3 s of silent pre-roll; (3) the recognizer stopped at the first pause, and not all recognizers honor the segmented session.
- **Fix (`transcription/SpeechPrep.kt`, pure logic with tests):** high-pass filter at ~80 Hz; voice detection by energy relative to the clip's own noise (30 ms frames, threshold = 2× the 20th percentile, minimum -66 dBFS); merges segments separated by pauses < 0.5 s into phrases, discards isolated bumps < 150 ms, splits phrases > 20 s at their quietest point, and keeps at most 12 phrases per clip; raises each phrase to -20 dBFS (maximum +30 dB, never lowers) with a soft limiter; 0.3 s of silence before and 1.2 s after.
- `SpeechTranscriber` recognizes each phrase with its own recognizer (no segmented session), with partial results as a fallback if the final result arrives empty or time runs out, and one retry on transient service errors. A phrase that fails does not bring down the others.
- If the recognizer fails, the clip says so ("The phone's speech recognizer couldn't handle this clip") instead of staying blank.
- On update, transcripts made with 0.5.0–0.5.1 are deleted once (`MurmurApp`, preference `maintenance/transcript_pipeline`) and redone automatically when each night is opened.

### 12.11 Repeated noises and category filter (0.6.0, 2026-11-05)

- **Problem on the phone:** sleeping with the fan on, one night saved 124 "wind" clips (the fan's airflow over the microphone produces gusts that exceed the baseline), and the 5 talking clips were lost among them.
- **Repeated noises (`core/classification/RepeatFilter.kt`, pure logic with tests):** if a clip ends up as OTHER with an ambient type (wind, fan, rain or room noise), the first one is saved normally and the following ones of the same type are added to it as repeats (no WAV and no new row) as long as 1 hour does not go by without hearing it; the hour counts from the last repeat. A clip in which YAMNet also heard talking, snoring or coughing (mean score ≥ 0.04) is never folded in: it is saved anyway. If the reference clip was deleted, a new one is saved.
- Database v3: `events.repeats` (NOT NULL DEFAULT 0) and `events.lastRepeatAt`; migration `MIGRATION_2_3`, existing nights are kept.
- Settings: "Save repeated noises only once" (on by default). Home shows "Repeated · wind (already saved)" in the recently heard list, and how many were counted without being saved.
- **Night detail:** episode filter by category with the number of clips in each (All · Snoring · Talking · Cough · Other; only the ones present). Tapping an episode of another category on the timeline clears the filter. The grouped clip says "Heard N more times, until HH:MM. Only this clip was saved." and "Sounds identified" counts all repeats.
- Tests: `RepeatFilterTest` and two full sessions in `SessionEngineTest` (4 gusts of wind → 1 clip with 3 repeats; with the setting off → 4 clips). Screenshot `14_detail_filter`.
- Nights already recorded are not compacted (their clips are still there); the filter helps to browse them.

### 12.12 Ignored sounds (0.7.0, 2026-11-05)

- **Request:** to be able to teach Murmur which sounds not to save (the dog, the air conditioner, the fridge…), as was done with the fan, but chosen by the user and for any type of sound.
- **On each OTHER clip**, an "Ignore this sound" button (not offered for snoring or cough, nor if that type is already ignored). It opens an "Ignore “Dog”?" dialog with the checkbox "Also delete the N clips of this sound already saved, from every night" (unchecked by default). This makes it possible to clean up old nights, such as the 124 wind clips.
- **Filter (`core/classification/IgnoreFilter.kt`, pure logic with tests):** an OTHER clip whose type (`SoundTypes.ofEvent`, the same one the detail screen shows) is on the list is not saved (neither WAV nor row), unless YAMNet also heard talking, snoring or coughing (`RepeatFilter.hasRealSound`). For voices, TV and laughter, talking does not protect the clip (those sounds already sound like talking); snoring and coughing do. It runs before repeat grouping.
- The list is stored in DataStore (`ignoredSounds`, `SoundType` names). The service follows it live: marking a sound in the middle of the night takes effect from that moment.
- Settings → "Ignored sounds": the list with "Stop ignoring", or how to add one if it is empty. Home shows "Ignored · Dog (on your list)" and how many were ignored during the night.
- Repository: `clipsOfType` (OTHER clips of one type across all nights) and `deleteEvents` (deletes rows and WAVs and rebuilds the episodes once per night). No database change (still v3).
- Tests: `IgnoreFilterTest` (4) and three in `SessionEngineTest` (ignored wind → 0 clips; ignoring another type has no effect; deleting the clips of one type). Screenshot `15_ignored_sounds`.

### 12.13 Repository in English, stored values migrated (0.8.0, 2026-11-06)

- Comments, KDoc, log and exception messages, test names, this plan, the README and the logo page are in English. A token comparison against the previous commit, ignoring comments and string literals, showed only renames.
- Renamed: `DetectorState` and `LivePhase` values (`CALIBRATING`, `LISTENING`, `ONSET`, `EVENT`, `PAUSED`), the navigation routes, thread names and screenshot file names.
- **Stored values in English, with a migration.** Database v4 (`MIGRATION_3_4`) rewrites the Spanish values already saved, without changing the schema:
  - `Category`: `RONQUIDO`, `HABLA`, `TOS`, `OTRO`, `FONDO` → `SNORING`, `SPEECH`, `COUGH`, `OTHER`, `BACKGROUND` (`events.category`, `events.userCategory`, `episodes.category`).
  - `SessionStatus`: `EN_CURSO`, `COMPLETADA`, `INTERRUMPIDA` → `IN_PROGRESS`, `COMPLETED`, `INTERRUPTED`.
  - `PauseReason`: `MICROFONO_OCUPADO`, `LLAMADA`, `ERROR_AUDIO`, `USUARIO` → `MIC_BUSY`, `CALL`, `AUDIO_ERROR`, `USER`.
  - `events.otherLabel`: `Desconocido` → `Unknown`, `Sin clasificar` → `Unclassified`. This also fixes the English UI, which showed these two labels in Spanish.
  - The history summary query compares against the English names.
- Settings (DataStore): `Sensitivity` is now `LOW`/`MEDIUM`/`HIGH` and `Retention` is `FOREVER`/`DAYS_7`/`DAYS_30`. Values saved with the old Spanish names are still read (`storedSensitivity`, `storedRetention`) and are written in English the next time the setting changes.
- Kept: the package `com.nochesonora` and the database file `nochesonora.db` (changing them would install a separate app or lose the nights), the Spanish UI translation in `res/values/`, the Spanish YAMNet display names in `Sounds.kt`, and two diagnostic messages that Home shows directly (`SessionEngine.lastError`).
- Tests: `EnglishValuesMigrationTest` (Spanish rows from version 3 → English, including the history summary; legacy settings names).

### 12.14 Transcription language (0.8.1, 2026-11-06)

- **Problem on the phone:** a clip that clearly said "la última prueba que veo es del 28" was transcribed as "Hello is". The recognizer was working in English.
- **Causes covered:** the transcription language was always the app language, so with the app in English it transcribed in English. And when the phone gave no language data (`checkRecognitionSupport` returns nothing), Murmur tried anyway without saying so, and the on-device recognizer can then silently fall back to another installed language.
- **Settings → "Language you speak"** (App language / Español / English): the language of the clips, independent of the UI language. Changing it clears the transcripts so they are redone in the new language.
- **Night detail** always says which language is used ("Transcribed in Spanish (es-CO)"). If the phone did not confirm the language (`Ready.confirmed = false`), it warns and offers "Download language to transcribe" (`triggerModelDownload`).
- Every existing transcript is redone once (`TRANSCRIPT_PIPELINE = 3`), since some came out in the wrong language.

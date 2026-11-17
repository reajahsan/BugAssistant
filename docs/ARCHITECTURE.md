# Architecture

## Layers

```
app (Compose)          MainActivity ─▶ MainViewModel          (file picker, SEND/SEND_MULTIPLE/VIEW share target)
                                           │
core/diagnostics (Kotlin)                  ├─▶ BugReportParser ── bug report zip/txt (streamed)
                                           ├─▶ DeviceCollector ── DropBox / logcat / ApplicationExitInfo, /proc/meminfo
                                           │        └─ CrashParser, LogcatParser ─▶ IssueGrouper ─▶ DeviceReport ─▶ ReportDigest
                                           │
core/storage (Room)                        ├─▶ ReportRepository ── reports / issue_groups / process_stats (reports.db)
                                           │
core/model (Kotlin)                        ├─▶ ModelDownloader ── HTTP Range, SHA-256 ──▶ <files>/<model>.gguf
                                           │
core/llama (Kotlin)                        └─▶ LlamaEngine ── single "llama-inference" thread
                                                    │
core/llama (JNI)                                    ▼
                                              LlamaNative (external funs)
                                                    │
core/llama (C++)                              llama-android.cpp  ── Session { model, ctx, vocab, atomic cancel }
                                                    │
third_party                                   llama.cpp / ggml (CPU backend)
```

Dependencies point downward only. `app` never touches JNI types, `core/llama` has no UI or networking code, and `core/diagnostics` knows nothing about the model.

## Crash and memory analysis

On launch the app scans the device and downloads/loads the model in parallel. When both are done, `ReportDigest` turns the report into a bounded plain-English digest (about 6,000 characters) and the model streams a triage summary: overview, top issues with likely cause and fix, and memory pressure. Grouped issues and counts are shown without the model, so the screen is useful even before it loads.

### Data sources

Android only lets an app read other apps' crashes with privileged access. `DeviceCollector` picks the best tier available:

| Access | Needs | Crashes / native crashes | ANRs | Low-memory kills |
|---|---|---|---|---|
| `FULL` | `READ_LOGS` + Usage access | DropBox `*_crash`, `*_native_crash`, `SYSTEM_TOMBSTONE` (full stack) | DropBox `*_anr`, `system_server_watchdog` (main-thread stack, lock waits) | logcat `lowmemorykiller` |
| `LOGS_ONLY` | `READ_LOGS` | logcat `crash` buffer (`AndroidRuntime`, `DEBUG`) | logcat `events` `am_anr` (reason only) | logcat `lowmemorykiller` |
| `OWN_APP` | nothing | this app's `ApplicationExitInfo` (API 30+) | same, with trace | same |

Memory (RAM, low-memory threshold, swap) comes from `ActivityManager.getMemoryInfo` and `/proc/meminfo`, which need no permission. DropBox `*_lowmem` entries are counted as system low-memory events.

Grant full access once per install:

```
adb shell pm grant com.reaj.bugassistant android.permission.READ_LOGS
adb shell appops set com.reaj.bugassistant GET_USAGE_STATS allow   # or Settings > Usage access
```

Restart the app after granting `READ_LOGS` (logcat access is fixed at process start). On Android 13+ the system may also ask the user to allow access to all device logs.

### Grouping

Occurrences are grouped by kind, process, root exception type (the last `Caused by:`) or signal/ANR reason, and the first non-framework frame without line numbers, so the same bug from different builds groups together. The same native crash reported twice (app tag and `SYSTEM_TOMBSTONE`, same PID within 60 s) is counted once. Groups are ranked crashes → ANRs → memory, then by count and recency.

All parsing is pure Kotlin and unit tested on the JVM (`core/diagnostics/src/test`).

## Bug report import

A bug report (Developer options › Take bug report, or `adb bugreport`) covers every app without any permission, so it is the main input for field devices. It arrives through the system file picker (`OpenDocument`) or as a share (`SEND`, `SEND_MULTIPLE` — Android shares the zip together with a screenshot, and the zip is picked — or `VIEW`). The activity is `singleTop`, so a share while the app is open goes to `onNewIntent`.

`BugReportParser` streams the input and never holds the whole file:

- **Zip or text** is detected from the `PK\3\4` magic bytes. In a zip it reads the main `bugreport*.txt` (or `dumpstate-*.txt`, the name some devices use), `FS/data/tombstones/tombstone_NN` and `FS/data/anr/*` (up to 64 files each, 128K characters per file).
- The main text is split by `------ SECTION ------` headers and `DUMP OF SERVICE` blocks. Only useful sections are kept: SYSTEM/EVENT/LAST LOGCAT (pre-filtered by tag), MEMORY INFO, SYSTEM PROPERTIES, DROPBOX, VM TRACES AT LAST ANR, TOMBSTONE, and the `cpuinfo`, `meminfo`, `diskstats` and `thermalservice` dumps.
- Timestamps are device wall-clock time, converted with `persist.sys.timezone` from the report.
- Duplicates (e.g. `am_crash` + `AndroidRuntime`, or `am_anr` + an ANR trace file) collapse in `IssueGrouper` by kind, PID and time. `am_crash` is `[user,pid,…]` up to Android 15 and `[pid,user,…]` on Android 16, so the larger number is taken as the PID. A native `am_crash` carries system_server's PID, so it has no PID and is matched to its tombstone by process name.
- `DeviceHealth` holds the slowness signals: uptime, load averages and total CPU, busiest processes, thermal status, free storage, largest PSS users, and skipped frames / `Davey!` jank per process.
- **Memory-leak suspects** (`IssueKind.MEMORY_LEAK`, one group per app and signal) come from three heuristics:
  - ART log lines: `Throwing OutOfMemoryError` → "OutOfMemoryError thrown"; `Clamp target GC heap` / `Forcing collection of SoftReferences` → "Java heap at its limit". These are matched by message text, because the tag is `art` or the process name.
  - Per-app `** MEMINFO in pid N [pkg] **` blocks in the `meminfo -a` dump. The Dalvik Heap row's Heap Alloc is compared to `dalvik.vm.heapgrowthlimit` (or `dalvik.vm.heapsize` when the heap is already above it, i.e. `largeHeap`). At ≥ 85 % the app is reported as "Java heap near its limit".
  - Objects with `Activities` ≥ 5 → "Many Activity objects in memory" (leaked Activities are the most common Android leak).
  
  These signals point to a likely leak, not a proven one. A heap dump or LeakCanary is still needed to find the leaking reference. An `OutOfMemoryError` crash stays a `CRASH`; the ART line adds a separate leak signal.
- Caps: 100k log lines and 5,000 raw issues. A file with no bug-report text and no issues raises `NotABugReportException`.

Progress is reported from bytes read against the file size from `OpenableColumns`.

## Storage

`core/storage` is a Room database (`reports.db`, app-private, schema version 2) with four normalized tables and one view:

| Table | Contents |
|---|---|
| `reports` | Source (access level, file name), device, capture time and window, counts per kind, memory and health columns, notes, and a fingerprint |
| `issue_groups` | One row per group (latest occurrence, count, first/last seen, frames, indexed `packageName`), ordered by rank; cascade-deleted with the report |
| `process_stats` | Busiest CPU, largest memory and jank processes for health data |
| `summaries` | AI summary and model name per (report, app); `app = ''` is the all-apps summary |
| `app_issues` (view) | Issue rows joined with report source, device and `collectedAt`, for SQL filtering by `packageName` |

The fingerprint is a SHA-256 of source, file name, device, capture time (bug reports) and the group signatures with counts and last-seen times. Saving a report with an existing fingerprint returns the stored record, so repeat scans on launch and re-imports of the same file do not create duplicates. A stored summary for the current app filter is reused instead of re-running the model. Bug reports are always saved in full (unfiltered); live scans only when they found issues.

### App filter

`DeviceReport.forApp(pkg)` keeps groups whose package (process without `:suffix`) matches, plus `LOW_MEMORY` groups, which are device-wide. Memory and health data are always device-wide. The filter is stored in SharedPreferences; the saved-report list shows per-app counts. Schema v1 was never released, so v1 → v2 uses a destructive fallback; later versions need real migrations.

`ReportMapper` converts between `DeviceReport` and the rows and is round-trip tested on the JVM (`core/storage/src/test`).

## Sharing

**Export CSV** writes `app_issues` rows (filtered by the selected app) through `IssueCsv` to a file the user creates with the Storage Access Framework (`CreateDocument`), so no storage permission is needed. The output is RFC 4180 with a UTF-8 BOM, ISO-8601 UTC times and formula-injection guarding; it is unit-tested in `IssueCsvTest`.

**Share** sends `ReportDigest.shareText()` — the report, health data and AI summary as plain text (≤ 40,000 characters) — through the system chooser. Nothing is sent otherwise.

## JNI contract

| Kotlin | C++ | Notes |
|---|---|---|
| `loadModel(path, nCtx, nThreads): Long` | allocates a `Session*` | Returns an opaque handle, or `0` on failure. `n_batch = n_ctx`, so the whole prompt is decoded in one batch. |
| `formatChat(h, system, user): ByteArray` | `llama_chat_apply_template` | Uses the template embedded in the GGUF and falls back to raw text when there is none. |
| `generate(h, prompt, grammar, maxTokens, cb): Int` | decode → sample → `onToken` loop | Blocking. Returns the token count, or `-1` tokenize / `-2` context overflow / `-3` decode / `-4` grammar error. |
| `cancel(h)` | `session->cancel = true` | The only call that is safe from any thread. |
| `free(h)` | frees ctx, model, session | Must not race with `generate`; the engine guarantees this. |

Rules the bridge follows:

- **Text is UTF-8 `byte[]`.** Never `NewStringUTF`, because of modified UTF-8. Partial multi-byte sequences are held in `pending` until they are complete.
- **The callback runs on the calling thread.** `generate` is invoked from the inference thread, so the `JNIEnv*` is valid and no `AttachCurrentThread` is needed.
- **Local refs are released per token**, and a pending Java exception from the callback ends the loop.
- **Grammar parse failures return `-4`** rather than adding a null sampler to the chain.

## Threading model

```
Main thread ──collect()──▶ Flow<GenEvent> (callbackFlow, UNLIMITED buffer)
                                ▲ trySend(Token)
llama-inference thread ── LlamaNative.generate() ── onToken(bytes)
```

- `LlamaEngine` owns a single-thread executor. `load`, `generate` and `free` all run on it, so access to a context is serialized without locks.
- Cancelling the collector triggers `awaitClose`, which calls `LlamaNative.cancel(h)`. The decode loop exits after at most one more token. The executor then frees resources in order.
- `LlamaEngine.close()` sets the cancel flag first, then queues `free` behind any running generation, so a session is never freed mid-decode.

## Model lifecycle

1. `MainViewModel.prepareModel()` runs on first launch.
2. `ModelSelector` picks the model from total RAM, `isLowRamDevice` and free storage:
   - **Llama 3.2 3B** (`Models.LARGE`) if RAM ≥ 5.5 GB as reported by Android (a "6 GB" phone) and the download fits with 512 MB to spare.
   - **Llama 3.2 1B** (`Models.SMALL`) otherwise. The UI says why (limited RAM or storage).
   - If even 1B does not fit, the status shows how much space to free, and Retry checks again.
   Bytes already downloaded (`.part`) and other model files (deleted once a model is chosen) count as available space. An already downloaded 3B file is kept on capable devices even when storage is now low.
3. Model files other than the chosen one are deleted.
4. If `<externalFiles>/<spec.fileName>` is missing, `ModelDownloader` streams to `.part`, resuming with `Range: bytes=N-` if a partial file exists.
5. The `.part` file is hashed (SHA-256) and compared with `ModelSpec.sha256`. A mismatch deletes it.
6. An atomic rename moves it into place, and `LlamaEngine.load()` memory-maps it through llama.cpp.

The file lives in app-specific external storage. It needs no runtime permission, survives app updates, is removed on uninstall, and is not compressed, so `mmap` works.

## Known limits (v0.1)

- The download only runs while the app process is alive. It resumes on the next launch. WorkManager with a foreground service is planned.
- Inference is CPU only (`n_gpu_layers = 0`). GPU backends vary too much across devices to enable by default.
- Each `generate` call clears the KV cache, so there is no multi-turn conversation. That fits single-shot triage.
- A 3B model can still misread stack traces; the grouped issue list is the ground truth and the summary is a guide.
- The large model (Llama 3.2 3B, ~2 GB) needs about 6 GB RAM; `ModelSelector` picks the 1B model on smaller devices (see Model lifecycle). Model files that were not selected are deleted on launch.
- DropBox keeps about three days of entries, so the live scan window is three days. A bug report's window runs from its oldest log line to capture time (often hours for logcat). Tombstones survive reboots and can be months or years old; they are still listed, but they don't stretch the window, and a report note counts them.
- Bug report formats vary by vendor and Android version; unknown sections are skipped. Tested on synthetic fixtures and on a real Samsung Android 16 report generated with Crash Lab (see [TESTING.md](TESTING.md)).
- Memory-leak detection works on bug reports only (live scans have no per-app meminfo) and uses fixed thresholds (85 % heap, 5 Activities). It flags suspects; it does not trace the leak.
- The database has no migrations yet (schema version 2; v1 is dropped destructively).
- The build is arm64-v8a only.

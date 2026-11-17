# Testing BugAssistant

## Unit tests

```powershell
.\gradlew.bat :core:model:testDebugUnitTest :core:diagnostics:testDebugUnitTest :core:storage:testDebugUnitTest
```

`CrashLabScenarioTest` parses a bug report as it looks after using every Crash Lab button (below). It checks that each fault is detected and attributed to `com.reaj.crashlab`.

## On-device test with Crash Lab

`samples/crashlab` is a test-only app (`com.reaj.crashlab`) that crashes, hangs and leaks on purpose. Never ship it.

1. Install both apps:
   ```powershell
   .\gradlew.bat :app:installDebug :samples:crashlab:installDebug
   ```
2. Open **Crash Lab** and run the test cases below. After each crash, reopen Crash Lab.
3. Finish with the leak cases (TC-6, TC-7) and **leave Crash Lab open**. Memory is measured when the bug report is taken, so a closed app shows no leak.
4. Take a bug report: **Developer options → Take bug report → Full report**, or `adb bugreport crashlab.zip` and copy the zip to the device.
5. In BugAssistant, choose **Import bug report** (or share the report from the notification), then set **App** to `com.reaj.crashlab`. In the picker's **Bug reports** folder, pick the large `dumpstate-….zip`, not the small `dumpstate-….txt` next to it (that is only a status file).

| # | Crash Lab button | Expected in BugAssistant |
|---|---|---|
| TC-1 | Crash: exception on main thread | App crash, `java.lang.IllegalStateException`, top frame `Faults.javaCrash` |
| TC-2 | Crash: exception on worker thread | App crash, `java.lang.IllegalArgumentException`, thread `crashlab-worker` |
| TC-3 | Native crash (SIGSEGV) | Native crash, `SIGSEGV` (from the tombstone) |
| TC-4 | ANR: freeze 20 s, then tap the screen a few times until the "isn't responding" dialog appears | ANR with `Faults.freezeMainThread` in the main-thread stack |
| TC-5 | Crash: OutOfMemoryError | App crash `java.lang.OutOfMemoryError` **and** memory-leak suspects "OutOfMemoryError thrown" / "Java heap at its limit" |
| TC-6 | Leak 10 Activities (tap it twice) | Memory-leak suspect "Many Activity objects in memory" (about 20 Activities) |
| TC-7 | Fill Java heap to 90% | Memory-leak suspect "Java heap near its limit" (about 90%) |
| TC-8 | Release leaks, then take another report | No "Many Activity objects" or "near its limit" for Crash Lab |

Also check:
- **App filter:** with `com.reaj.crashlab` selected, only Crash Lab issues (plus device-wide low-memory events) are shown; the saved-report row counts match.
- **AI summary:** the Memory section names Crash Lab's leak suspects and suggests a heap dump or LeakCanary.
- **Export CSV:** the file has one row per issue group, including the `MEMORY_LEAK` rows.

Notes:
- The OOM crash and the ART heap lines depend on the device's heap limit (`dalvik.vm.heapgrowthlimit`, often 256–512 MB); filling the heap can take a few seconds.
- Logcat in a bug report only reaches back a few hours on a busy device, so take the report soon after the test.
- The ANR needs input while the main thread is blocked; without a tap there is no ANR.

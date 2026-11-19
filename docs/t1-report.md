# TASK T1 report — restart seq, remembered-owner reconnect, keepalive flapping, blank Event log

Branch: `fix/t1-seq-reconnect-log` (tag `before-t1`). Evidence: docs/evidence/run2-A54.txt / run2-A55.txt.

Status per part is updated as work progresses; final state in Part E.

## Part A — Event log screen
- Status: DONE
- Root cause of the blank screen (evidence 4): MeshLogTab rendered rows only in the branch
  `else if (visible.isEmpty()) { items(visible, ...) }` — i.e. exactly when there was nothing to
  show. With 613 lines the toolbar counted them but the list body never composed any row.
- Fix: render `renderedLogLines(visible)` whenever non-empty; explicit empty state
  ("No lines match this filter" / "No log lines yet."). List now has `weight(1f)` in a Column,
  chips are one horizontally scrollable Row (was FlowRow), toolbar is one compact row: Follow
  switch, line count, Copy, Clear and a three-dot menu holding Share log + Previous session.
  Follow stays ON by default; newest line at the bottom; monospace wrapping rows unchanged.
- New pure helper `renderedLogLines` (cap `MAX_RENDERED_LOG_LINES = 500`, keeps the newest) in
  MeshUiHelpers.kt; JVM test MeshLogTabModelTest asserts count == min(500, matches), newest kept,
  empty case — 3/3 green.
- Debug line once per open: `ui: log screen lines=N rendered=M` via a NEW optional parameter
  `onUiEventLog: (String) -> Unit = {}` on MeshLogTab (default keeps other call sites unchanged);
  MeshScreen passes its existing logger.
- build.gradle.kts: added `androidx.compose.material:material-icons-core` (BOM version) for the
  MoreVert icon — no version catalog entry existed.
- Instrumented test added: app/src/androidTest/.../ui/MeshLogTabAndroidTest.kt (createComposeRule,
  600 lines, asserts a row is displayed and list node height > 0). Run with
  `./gradlew :app:connectedDebugAndroidTest` — NOT run here.
- NOTE (pre-existing, not caused by T1): `compileDebugAndroidTestKotlin` fails on
  ChatUnreadInstrumentedTest.kt:129 — `for (s in store.summaries)` where summaries is a Flow
  (no iterator). That file has never compiled since it was committed in TASK R1 (3d85715), so the
  new instrumented test could not be compile-verified either. Per T1 rules I did NOT edit that
  existing test; the one-line mechanical fix would be `store.summaries.collect { s -> ... }`.

## Part B — persistent origin sequence
- B1 Router E1: DONE (commit 685e058). Two optional constructor params appended after `policy`:
  `initialSeq: Int = 0` and `onSeqAllocated: (Int) -> Unit = {}`; defaults reproduce today's
  behavior. nextSeq() reports every allocation through the callback. New RouterInitialSeqTest
  (5 tests): default start at 1, resume after stored value, wrap-around at 65535, callback fires
  for every allocation in order, initialSeq must fit 16 bits. All existing Router test classes
  unchanged and green.
- B2 SequenceKeeper + wire-in + localhost node test: DONE (commit hash filled in Part E).
  - routing/SequenceKeeper.kt (pure): stored value is a HIGH-WATER MARK running PERSIST_MARGIN=32
    ahead of the last allocated seq. Resume = stored + START_GAP=64, so the first post-restart
    frame is at least 82 numbers beyond the last used one — never inside any peer's 1024-slot
    dedup window, modulo 65536. Persists (current+32) every PERSIST_EVERY=16 allocations and on a
    clean stop(). Missing/corrupt/out-of-range storage starts at seq 1 exactly as before; a stored
    mark of 0 is treated as valid (it is what a wrapped persist writes).
  - node/SharedPreferencesSequenceStorage.kt: SharedPreferences adapter ("airmesh_node" prefs, key
    "seq_high_water_mark", decimal string, commit() so the mark is on disk before a crash can lose it).
  - AirMeshNode: new optional `sequenceKeeper: SequenceKeeper? = null` (default = old behavior);
    passes keeper.initialSeq to the Router and feeds every allocation back; logs
    "seq: resuming origin sequence at N" when resuming above 0; stop() persists via keeper.stop().
  - AirMeshNodeFactory.createAirMeshNode now constructs the keeper with the SharedPreferences
    storage, so MeshService (the only caller) gets it without any change there.
  - Tests: SequenceKeeperTest (7 tests, fake in-memory storage + fake clock): missing/corrupt/
    out-of-range start at 1; clean-stop restart never reuses a number; crash without stop still
    starts above the last used; wrap-around keeps the resume ahead of the high-water mark modulo
    65536 (runs across 65535->0 and checks the first post-restart seq is outside the newest-1024
    window); persist cadence every 16 with margin + fake-clock stamps; clean stop persists even
    without new allocations. AirMeshNodeSequenceRestartTest: real localhost sockets, node A sends
    20 messages + a DM (acked), stops cleanly and is rebuilt with the SAME storage, reconnects to
    B (never restarted, so its dedup window still holds every phase-1 seq) and sends 5 more + a
    second DM; asserts all 25 delivered exactly once, both DMs acked, zero "dropped duplicate"
    lines for node=1. Without the keeper A would restart at seq 1 and B drops the whole batch —
    this is the exact failure of evidence (2). All green: SequenceKeeperTest 7/7,
    AirMeshNodeSequenceRestartTest 1/1; :app:compileDebugKotlin clean.

## Part C — remembered-owner reconnection
- C1 RememberedOwnerStore: DONE (commit hash filled in Part E)
  - transport/RememberedOwnerStore.kt (pure): Record(deviceAddress, nodeId, recordedAtMs,
    failedAttempts, nextAllowedAttemptAtMs); one pipe-separated string on disk so ANY corruption
    reads as "no record"; cleared after MAX_FAILED_ATTEMPTS=3 failures or once older than
    MAX_RECORD_AGE_MS=24 h (expiry clears the storage on read).
  - service/SharedPreferencesRememberedOwnerStorage.kt: adapter in the same "airmesh_node" prefs
    file as the sequence mark; commit() so the record is on disk before a crash can lose it.
  - MeshService: on a role transition into CLIENT while formed, rememberOwnerIfClient() polls up
    to 15 s (500 ms) for BOTH the CONNECTED radio peer's deviceAddress — a MAC, which is what
    manager.connect() needs later; the connection info's groupOwnerAddress is an IP and unusable —
    AND our only live neighbor (the owner itself in hub topology), then records and logs
    "auto: remembered owner #id at address". Not gated on the auto switch: remembering is inert
    unless the C2 policy acts, so manual mode is unaffected.
  - Tests: RememberedOwnerStoreTest 7/7 green (round-trip incl. restart from the same storage;
    expiry boundary exactly at 24 h + self-clearing; 3-failure clear; refreshSuccess reset of
    count/pacing/timestamp; noteAttempt stamp; corrupt inputs -> null; mutating methods are no-ops
    without a record). :app:compileDebugKotlin clean.
- C2 RememberedOwnerPolicy in AutoMeshController: PENDING
- C3 ownerProbe experiment + 25 s watchdog: PENDING

## Part D — keepalive and flapping
- D0 findings (max 25 lines): PENDING
- D1 per-socket keepalive loop fix: PENDING
- D2 tolerant defaults + grace write + flap guard: PENDING
- D3 re-registration coalescing: PENDING

## Part E — final verification
- PENDING

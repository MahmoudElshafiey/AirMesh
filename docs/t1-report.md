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
- B1 Router E1: PENDING
- B2 SequenceKeeper + wire-in + localhost node test: PENDING

## Part C — remembered-owner reconnection
- C1 RememberedOwnerStore: PENDING
- C2 RememberedOwnerPolicy in AutoMeshController: PENDING
- C3 ownerProbe experiment + 25 s watchdog: PENDING

## Part D — keepalive and flapping
- D0 findings (max 25 lines): PENDING
- D1 per-socket keepalive loop fix: PENDING
- D2 tolerant defaults + grace write + flap guard: PENDING
- D3 re-registration coalescing: PENDING

## Part E — final verification
- PENDING

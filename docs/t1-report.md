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
- C2 RememberedOwnerPolicy in AutoMeshController: DONE (commit hash filled in Part E)
  - transport/RadioPeer.kt (pure): deviceAddress + raw WifiP2pDevice status; constants
    AVAILABLE=1 / CONNECTED=3 mirroring the framework values.
  - transport/RememberedOwnerPolicy.kt (pure, fake clock + seeded Random): consider() returns a
    Target only when ALL hold — searching for >= MIN_SEARCHING_MS=12 s, a record exists, it is
    younger than RECORD_MAX_AGE_MS=30 min, fewer than 3 failed attempts, the pacing stamp has
    elapsed, and the remembered address appears in the latest requestPeers list with status
    AVAILABLE. On success it stamps nextAllowedAttemptAtMs = now + ATTEMPT_SPACING_MS=45 s + up to
    SPACING_JITTER_MAX_MS=2 s of jitter (so phones do not retry in lockstep) and returns the
    1-based attempt number for logging. noteFailure/noteSuccess delegate to the store.
  - AutoMeshController: new optional ctor param `rememberedOwnerPolicy: RememberedOwnerPolicy? =
    null` (null = old behavior, all existing tests untouched); new event RadioPeersChanged(peers)
    that only stores the latest raw list; decide() rule (d) — peers.isEmpty(), i.e. NO DNS-SD peer
    is accepted — consults the policy before giving up, and on a Target reuses the EXISTING
    Joining/ConnectTo flow (same 40 s timeout, same retries). The attempt is logged via a new
    drain takeRememberedOwnerLogLine() as "auto: remembered-owner attempt #N to #<nodeId>
    <address> reason=dnssd-silent". Outcome hooks: ConnectFailed / GroupGone while Joining count a
    failure ONLY for remembered joins (a flag cleared in enterSearching/enterIdle so it can never
    leak into a later normal join); GroupFormed refreshes the record. The policy is only reachable
    from Searching with auto on and no manual pause, so those preconditions hold by construction;
    it NEVER creates a group.
  - MeshService: feeds RadioPeersChanged from the connector.state collector — WifiP2pDevice has no
    structural equality, so the raw list is compared by content via (address to status) snapshots
    and only changes are forwarded (no per-callback spam); both autoEvent log lines exclude
    RadioPeersChanged for the same reason. The controller now gets rememberedOwnerPolicy =
    RememberedOwnerPolicy(rememberedOwnerStore).
  - Tests: RememberedOwnerPolicyTest 9/9 (every precondition incl. exact age boundary, pacing
    stamp, 3-failure cap, seeded-jitter determinism with two policies on the same seed) and
    AutoMeshControllerRememberedOwnerTest 8/8 — including the EXACT evidence-(1) scenario: record
    at t=0, peer visible in requestPeers from +7 s, silent DNS-SD, no attempt before +12 s, one
    ConnectTo with the spec log line at exactly +12 s; plus pause/resume (the 12 s minimum restarts
    with the resume), a visible DNS-SD owner joins normally and does NOT touch the record (its
    failure is not counted), 45 s+jitter pacing after a failure, the 3-attempt cap clearing the
    store, success refreshing the record so numbering restarts at #1, auto-off gating, and a
    non-AVAILABLE peer never triggering. Existing AutoMeshControllerTest stays green: 43/43.
- C3 ownerProbe experiment + 25 s watchdog: DONE (commit hash filled in Part E)
  - DiscoveryCoordinator (pure): new optional ctor param `stuckCycleMs: Long = STUCK_CYCLE_MS`
    (between burstOffsetsMs and clock; the old 90 s default keeps every other caller unchanged)
    used by releaseStuckCycleIfAny. New public `var ownerProbeActive` + private lastProbeAtMs:
    while set, tick() issues one-shot strategy-3 scans (discoverPeers then services — the
    DnsSdRefreshSequencer plan for 3) on their own OWNER_PROBE_INTERVAL_MS=20 s cadence, ahead of
    the merge-scan gate and the burst/refresh branches; each scan releases the radio on completion
    so the next firing starts a fresh cycle. CycleComplete / refreshIfDue / rotateIfSilent got the
    matching `|| ownerProbeActive` special cases (rotation never issues its own scan in probe mode,
    like it does not in gate mode); stopService() clears both probe fields. A just-released stuck
    cycle is re-issued on the same tick because its 20 s interval has already elapsed.
  - MeshService: passes `stuckCycleMs = 25_000L` at coordinator construction (evidence 5: one lost
    callback kept an empty owner silent for over 94 s). New OwnerProbeFlagStore (interface +
    SharedPreferences impl, "airmesh_service" prefs, key "owner_probe_enabled", default OFF) copying
    the recovery-supervisor pattern; `_ownerProbeEnabled` StateFlow + setOwnerProbeEnabled(). The 1 s
    tick loop tracks an empty-owner period: probeWanted = flag && auto on && group formed && role
    OWNER && liveNeighborCount == 0 (gated on the auto switch so a manually created group is never
    touched); after OWNER_PROBE_MIN_EMPTY_MS=10 s it sets coordinator.ownerProbeActive and logs
    "dnssd: owner probe started/stopped"; activation also calls setDiscovering(true) (idempotent —
    makes sure the directory's 5 s loop runs). Deactivation only clears the flag, so the previous
    cadence resumes untouched.
  - UI: Debug card switch "Owner discoverability probe (experimental)" threaded
    MeshService -> MeshActivity -> MeshScreen -> MeshNetworkTab -> DebugCard (debuggable builds only),
    same pattern as the recovery-supervisor switch.
  - Tests: DiscoveryCoordinatorOwnerProbeTest 4/4 — probe cadence on the 5 s directory grid
    (scans at exactly 0/20k/40k/60k/80k/100k, all strategy=3, six "dnssd: owner probe cycle" lines);
    probe beats the merge-scan gate (20 s cadence + forced strategy 3 instead of the gate's 15 s /
    strategy 2); a stuck addServiceRequest is released at exactly the custom 25_000L boundary and
    re-issued on the same tick, then back to the 20 s cadence; stopService leaves probe mode.
    Existing DiscoveryCoordinatorTest 16/16 and DiscoveryCoordinatorOwnerScanTest 4/4 stay green
    (default stuckCycleMs = old 90 s behavior). :app:compileDebugKotlin clean.
- End of Part C full suite: 1070 tests, 1 failure — the known pre-existing S2 baseline
  TcpMeshTransportKeepaliveTest.keepaliveHellosNeverReachIncoming (pending user decision; T1 adds
  zero new failures). Count check: 1042 at end of B + 7 (C1) + 17 (C2) + 4 (C3) = 1070.

## Part D — keepalive and flapping
- D0 findings:
  - Clock/tick: wall clock throughout. ONE global writer coroutine (started once in start()) ticks
    delay(interval/2) on the transport dispatcher (Dispatchers.IO), reads System.currentTimeMillis()
    and writes a HELLO to every peer whose lastActivityMs is >= interval old.
  - "Idle" = per-socket PeerConnection.lastActivityMs, refreshed by ANY successful read or write: an
    incoming keepalive suppresses the local writer for another full interval.
  - Duplicate sockets: register() replaces the map entry for a node id and closes the old
    PeerConnection outside the lock; the loop iterates a snapshot, so it may write once to an already-
    replaced socket — writeToPeer catches the IOException and removePeer(key, oldValue) is a no-op pair
    removal (safe, at most one spurious "write failed" line).
  - Raw reachability probe: WifiDirectConnector opens a plain TCP connect to PORT without HELLO and
    closes it; the owner accepts, sends its HELLO, gets EOF, logs a handshake failure. No peer is
    registered, so keepalive never touches it; it only adds one extra connection per client connect-loop
    restart (i.e., after every flap).
  - Why a socket can go 24 s without writes: (1) incoming-traffic suppression — the side that keeps
    receiving (even just the peer's keepalives) never writes, so all bytes in one direction come from a
    single writer; with phase offset between the two loops the suppressed side stays silent while the
    sender's own reader starves; (2) worst-case write gap on the sending side is interval + tick = 1.5x
    interval, and a phase-offset pair can go up to ~3x interval ≈ 24 s without a byte at production
    settings — exactly deadAfterMs: zero margin; (3) one global loop: a blocking write to an unresponsive
    peer (full TCP buffer) delays keepalives for ALL other peers; (4) wall clock + delay() drift
    (NTP/suspend, IO load) stretches gaps further. Any Wi-Fi Direct jitter or one dropped HELLO tips a
    healthy idle link over its own 24 s deadline → close → immediate reconnect = evidence (3).
- D1 per-socket keepalive loop fix: DONE (commit hash filled in Part E)
  - TcpMeshTransport: the single global writer is gone. register() now launches ONE coroutine per
    live socket into this start's scope (removePeer/stop cancel it); each loop does delay(interval)
    on the transport dispatcher and writes a HELLO when that socket's last gate activity is >= one
    interval old — so the gap between two writes on one socket is bounded by ~2 x interval, and a
    slow write to one peer can no longer delay the others. All stamps are System.nanoTime()
    (monotonic): NTP steps or suspend/wake cannot distort the cadence.
  - GATE DEVIATION from the spec wording: the spec says "write if (now - lastWriteAt) >= interval"
    (own writes only). That breaks existing test (e) activeTrafficSuppressesKeepaliveWrites, which
    requires incoming MSG frames to suppress keepalive writes — with an own-writes-only gate the
    first check at t≈250 ms lands inside the 600 ms active phase and a write happens. Settled gate:
    max(last own write, last incoming NON-HELLO frame); incoming keepalive HELLOs NEVER refresh it
    (that suppression was D0 root cause 1). Real data still suppresses probing, so (e) passes.
    Residual risk documented: a continuous one-way data flow can starve the sender's own writer —
    mitigated by D2's grace write + flap guard.
  - PeerConnection: monotonic activityAtNanos; per-socket keepAliveSentCount/keepAliveRecvCount;
    maxWriteGapNanos (largest gap between consecutive successful writes); noteKeepaliveReceived /
    noteIncomingData / noteKeepaliveSent; readFrame no longer touches the gate — the reader decides
    (corrupt frames count as data: bytes arrived). New public peerKeepaliveStats() + top-level
    PeerKeepaliveStats(neighborId, sent, received, maxSilenceMs). Global keepAliveCount kept.
  - MeshService: the single "tcp: stats keepAlive=N" line is replaced by one line per live peer in
    the same 60 s block: "tcp: stats #id keepAliveSent=N keepAliveRecv=M maxSilenceMs=X".
  - New TcpMeshTransportPerSocketKeepaliveTest (real loopback sockets, port 0, condition waits):
    (1) idleLinkStaysUpForTwentyIntervals — interval 150 ms / deadAfter 3 s; both sides reach
    sent >= 20 (~3+ s of pure keepalive), link still up, no "timed out" line, received >= 18 both
    ways. (2) twoLinksInBothDirectionsNeverExceedDoubleIntervalSilence — chain N1->N2->N3 so the
    middle node holds one outbound + one inbound socket; interval 400 ms / deadAfter 8 s; after all
    four endpoints reach sent >= 5, maxSilenceMs <= 2 x interval + 1 s slack on EVERY socket.
    Spec checklist mapping: "silent peer dropped after dead timeout" = existing (b); "keepalives
    never reach incoming" = existing (c) — the known-failing S2 baseline (it awaits log lines that
    no longer exist since keepalive HELLOs are counted silently; cannot edit per T1 rules, stays
    red, reported here); "default parameters behave as before" = existing (d).
  - Result: new class 2/2 green; existing TcpMeshTransportKeepaliveTest 5/6 with ONLY the known
    baseline failure keepaliveHellosNeverReachIncoming ("timed out after 10000ms waiting for
    keepalive HELLOs flowed both ways"); :app:compileDebugKotlin clean. Zero new failures.
- D2 tolerant defaults + grace write + flap guard: DONE (commit hash filled in Part E)
  - MeshService defaults: keepAliveIntervalMs 8 s -> 5 s, deadAfterMs 24 s -> 40 s. New optional
    ctor param silenceGraceCheck: (() -> Boolean)? = null on TcpMeshTransport (pass-through added
    to AirMeshNodeFactory); MeshService passes "group formed AND p2p interface has its mesh
    address" (connector.state.value.groupFormed && NetworkDiagnostics.hasGroupAddress(enumerate()),
    evaluated lazily at silence time — long after connector exists).
  - Grace: on a full-deadline silence the reader records the timeout, then if the check says true
    logs "link to #id silent for Xs but the group is still formed; one keepalive and 5 s more",
    writes ONE keepalive (counted in that socket's sent counter) and waits SILENCE_GRACE_WAIT_MS=5 s
    for an answer. Any frame arriving inside the window is processed normally and the link lives on;
    otherwise the EXACT old line "link to #id timed out (no data for Xs)" is logged and the reader
    ends through the usual close path. null/false check = today's immediate drop, byte-for-byte.
  - Flap guard: per node id (kept across reconnects on purpose — the flap pattern is timeout ->
    close -> reconnect -> timeout again), monotonic-nano stamps inside FLAP_GUARD_WINDOW_MS=5 min;
    more than FLAP_GUARD_THRESHOLD=3 timeouts in the window raises that peer's effective deadline to
    flapGuardRaisedTimeout(base) = base*3/2 capped at FLAP_GUARD_MAX_DEAD_AFTER_MS=90 s (pure top-
    level fun for unit tests); when the count cools back down it returns to base. The reader applies
    deadAfterMsFor(id) before every blocking read, so a raised deadline takes effect from the next
    silence episode and is reflected in its "no data for Xs" line; each raise transition is logged
    ("flap guard: peer #id timed out 4+ times in 5 min; dead timeout raised to Xs").
  - New TcpMeshTransportSilenceGraceTest (real loopback sockets, port 0, condition waits):
    (1) flapGuardRaisesDeadTimeoutAfterRepeatedSilence — base 2 s; five silent raw-peer episodes:
        exactly four "no data for 2s" drops, the raise log line, deadAfterMsFor(99)==3_000L and a
        fifth drop logged as "no data for 3s". (2) flapGuardRaiseIsFiftyPercentCappedAtNinetySeconds
        — pure: 8 s->12 s, 45 s->67.5 s, 70 s capped at 90 s. (3) graceKeepaliveKeepsLinkWhenPeer-
        AnswersLate — check {true}, peer silent past the 2 s deadline then one MSG at +2.5 s via a
        scheduled coroutine: link still up at +8 s, no "timed out" line, grace line logged, frame in
        incoming. (4) graceFailsWhenPeerStaysSilent — dropped at ~deadline+5 s with the exact
        timeout line and the logged grace attempt. (5) graceCheckFalseDropsImmediatelyWithoutGrace —
        no grace line, plain-deadline drop. Spec checklist mapping: "default parameters behave as
        before" = existing keepalive tests (b)/(d) with null check; "peer that goes silent after the
        handshake is dropped after the dead timeout" = existing (b).
  - Result: new class 5/5 green; TcpMeshTransportKeepaliveTest still 5/6 (only the known baseline
    failure); TcpMeshTransportPerSocketKeepaliveTest still 2/2; :app:compileDebugKotlin clean. Zero
    new failures.
- D3 re-registration coalescing: PENDING

## Part E — final verification
- PENDING

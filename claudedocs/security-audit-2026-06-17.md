# JBotWithUsV2 — Java-side Security Audit

**Date:** 2026-06-17 · **Branch:** `sdn-test-harness` · **Scope:** all six Gradle modules (`api`, `core`, `cli`, `quest-core`, `test-support`, `example-script`)
**Method:** six parallel domain auditors (supply-chain, crypto/SDN, runtime/isolation, IPC/RPC, native FFI, CLI/API). Read-only; no code modified. Highest-impact and most-surprising findings spot-verified by the consolidator (see *Verification notes*).

---

## Executive summary

The Java side is **defensively well-engineered**. There is **no confirmed memory-safety bug, no RCE in the Java code itself, no command injection, no Java deserialization gadget surface, and no XXE.** Panama + the JVM turn every malformed native offset/length into a bounds-checked exception (DoS at worst, not corruption), and the Maven install pipeline is fail-closed with a real staging→verify→move flow.

The genuine risk is concentrated at **four trust boundaries**:

1. **Maven supply chain** — well-defended (fail-closed, hardened XML, atomic 0600 creds), but ships a **known-vulnerable BouncyCastle** and **never checks PGP key revocation/expiry**.
2. **The native producer wire (pipe/SHM)** — geometry and ring-mask fields from the producer are **read but not range-validated** against the host's compiled layout → a buggy/compromised agent can wedge snapshot reads and the event stream (DoS).
3. **The native DLL load path** — `~/.botwithus/native/*.dll` and `-D` overrides are loaded with **no integrity/signature check and no path canonicalization** → RCE for anyone who can write the file (largely design-accepted; the launcher populates it out-of-band).
4. **The SDN signed-bundle path** — the Java side performs **zero** signature/AEAD verification; all integrity is delegated to the JVM-injected native `jdk.internal.sdn.SdnClassLoader`. **The decisive question — does that native loader fail closed? — cannot be answered from this repo** and must be audited in `NXTLibrary`.

**Trust-model framing (load-bearing):** local script JARs are **unsandboxed, fully-trusted code** by design — no `SecurityManager` (removed in Java 25), no permission model. Per the workspace `CLAUDE.md` and confirmed in code, this is intentional, single-operator. Therefore "a script can read files / open sockets / call native code" is **not** a finding. Findings only escalate where (a) an *external/cross-boundary* input (downloaded artifact, producer DLL, dropped file) is mishandled, (b) one script can attack *another* script beyond what it could already do, or (c) a control the project *claims to enforce* is not actually enforced.

| Sev | Count | Theme |
|-----|-------|-------|
| High | 6 (+1 open question) | Vulnerable crypto lib; no key revocation; DLL-load RCE; unvalidated producer wire (×2); reload classloader race; **SDN integrity is out-of-repo** |
| Medium | 9 | Signing defaults fail-open; SHA-1 fallback; SDN lockdown gating; unstoppable scripts; scripts-dir resolution; native NULL/unbounded-string deref; event-pump CPU DoS; racy enqueue |
| Low | 14 | Redirect cred leak; HTTP repos; ISC forgeable sender/no quotas; msgpack stack bomb; alloc caps; password echo; `adopt` path gap; telemetry gaps |
| Info / corrected | several | Unsandboxed-by-design; **stream `pipe_name` UNC vector corrected to non-issue**; key-ID-vs-fingerprint; dir perms |

---

## HIGH

### H1 — Known-vulnerable BouncyCastle pinned in the verification path
`gradle/libs.versions.toml:11` → `bouncycastle = "1.78.1"`, consumed at `core/build.gradle.kts`.
`bcprov/bcpg-jdk18on` 1.78.1 is within the affected range of **CVE-2026-0636** (LDAP injection via crafted X.500 names → can subvert cert/CRL lookup) and **CVE-2026-5598** (non-constant-time/FrodoKEM timing leak); both fixed in **1.84**. The 2024 CVEs (CVE-2024-30172 etc.) are already patched in 1.78.1. BC is the project's trust anchor (detached-PGP verification of untrusted downloaded JARs). The specific CVEs aren't in the OpenPGP detached-sig code path, so *direct* exploitability against the resolver is limited — but shipping a known-vulnerable crypto lib in the verification path is the wrong posture.
**Caveat (not a one-line bump):** `core/build.gradle.kts:23-28` documents that a prior bump past 1.78 broke JPMS module descriptors (bcpg `PGPUtil` static-init reaches `org.bouncycastle.asn1.cryptlib` across the module boundary → `NoClassDefFoundError` on the module path / jlink image). Upgrading to 1.84 needs re-derived `extraJavaModuleInfo` overrides in `:core` and `:cli` plus a jlink re-validation.
**Fix:** upgrade to 1.84+, re-derive module-info shims, re-run the jlink image build and PGP-verification tests.

### H2 — PGP verifier never checks key revocation or expiry *(verified)*
`core/.../resolver/pgp/BouncyCastlePgpVerifier.java:170-194` (`verifyAgainstKey`).
The verifier calls `signature.init(...)` then `signature.verify()` and nothing else — confirmed by direct read. It never calls `publicKey.hasRevocation()` and never checks the key's creation/expiry window. A signing key that is later **compromised and revoked** (or expired) remains fully trusted forever, so a stolen key can sign malicious script JARs that pass verification. (The trust gate itself is correct — keys must be in the trusted keyring, no verify≠trust bug — this is purely the missing revocation/expiry step.)
**Fix:** reject signatures from revoked keys (`hasRevocation()` + revocation-signature check) and from keys outside their validity window; consider honoring expiry on the binding signature.

### H3 — Native DLL load-path: no integrity check, no canonicalization, dependent-DLL search-order exposure
`core/.../util/NativeCache.java:84-112`, consumed at `core/.../cache/NXTCache.java:79-92` and `core/.../worldwalker/WorldWalker.java:65-74`.
The DLL is resolved from `System.getProperty("nxtcache.dll"|"worldwalker.dll")`, else `~/.botwithus/native/<name>`, with the only check being `Files.isRegularFile`. It is then handed to `SymbolLookup.libraryLookup(...)` (→ `LoadLibrary`) — **no canonicalization, no allow-listed root, no signature/hash gate.** Anyone who can write `%USERPROFILE%\.botwithus\native\NXTCache.dll`, influence the `-D` property/JVM args, or plant a dependent DLL earlier in the Windows search order (CWD/`PATH`) gets arbitrary native code mapped into the host JVM = in-process RCE. Not a Panama-checked condition — real code execution.
**Severity note:** this is largely **design-accepted** (the launcher populates the dir out-of-band and `System.loadLibrary`/JNI is banned), so it may be acceptable risk — but from a pure FFI-safety standpoint it is the highest-impact item in scope and the Java layer does zero gating.
**Fix:** verify the binary (Authenticode, or a pinned SHA-256 the launcher records) before `libraryLookup`; canonicalize and confirm the resolved path is under an expected root; constrain dependent-DLL resolution (`SetDefaultDllDirectories` / `LOAD_LIBRARY_SEARCH_*`) so dependents load only from system + the DLL's own directory.

### H4 — SHM mapping geometry is read but never range-validated against the compiled layout *(verified)* — DoS
`core/.../shm/SharedRegion.java:148-195` (`probeHeader`/`populateSlices`).
`probeHeader` reads `snapshotSize`, `snapshotOff0/1`, `ringOff`, `ringSize` from the producer header (correctly via `Integer.toUnsignedLong` — no signed-misread bug) but performs **no validation** that they are consistent with the host's compiled `Layout` (e.g. `snapshotSize >= Layout.SNAPSHOT_SIZE`, ring large enough, slices non-overlapping, total within a sane cap) before `populateSlices` feeds the producer offsets into `fullSlice.asSlice(off, size)`. A producer that keeps `version==15` (passing the magic/version/pid guards) but publishes a short/inconsistent geometry causes `asSlice`/fixed-offset reads to throw on **every** snapshot read.
**Impact:** DoS only — Panama bounds-checks `asSlice` and every `get`, so it's a thrown exception, not OOB memory disclosure. But it's an unhandled exception on the per-tick read path. (A bogus huge `ringOff+ringSize` also drives the full-region `MapViewOfFile` size.)
**Fix:** validate geometry against compiled constants in `probeHeader` and throw `SharedMemoryException` loudly — the same lockstep discipline already applied to `PROTOCOL_VERSION`.

### H5 — `EventRingReader` trusts the producer's `slotMask` with no validation — DoS / wrong-slot reads
`core/.../shm/EventRingReader.java:65-78` (ctor), `97-139` (`poll`).
`slotMask` is read once from the producer header and used directly to index ring slots (`(seq & slotMask) * EVENT_SLOT_SIZE`). It is never checked against the host's compiled `EVENT_RING_SLOTS`/`EVENT_SLOT_SIZE`. A negative mask (`0xFFFFFFFF`) or oversized mask drives the slot offset out of the ring slice → `IndexOutOfBoundsException` on every poll → the event stream is permanently dead (caught and re-logged each iteration). A mask crafted to stay in-bounds but ≠ `slotCount-1` can cause **silent wrong-slot reads** (a stale slot's `slotSeq` spuriously matching → a garbage event body decoded). The `fromHead=false` branch (`slotCount = sm+1`) also has an `Integer.MIN_VALUE` overflow for `sm == Integer.MAX_VALUE` (latent; prod uses `fromHead=true`).
**Impact:** DoS (dead/looping event stream) plus a data-integrity risk; not memory-unsafe.
**Fix:** in the ctor, read `slotCount` from the header, require `slotCount == EVENT_RING_SLOTS` (power-of-two `<=` cap), `slotMask == slotCount-1`, `slotMask > 0`; reject otherwise.

### H6 — Reload closes script ClassLoaders while old script threads may still be running
`core/.../runtime/LocalScriptLoader.java:115` (`previousLoaders.closeAll()`), `PreviousLoaderTracker.java:34`, driven by `cli/.../ReloadCommand.java:73-76` → `ScriptRuntime.stopAll():182-189`.
`stopAll()` calls `runner.dispose()` → `stop()` → `thread.interrupt()` (`ScriptRunner.java:146-151`) but **never `awaitStop()`** (which exists at `ScriptRunner.java:172`, unused on this path). Interruption is cooperative; a script with a tight CPU loop, a swallowed `InterruptedException`, or a blocking native/FFM downcall keeps running while `closeAll()` calls `URLClassLoader.close()` underneath it. Consequences: (1) surviving thread → `NoClassDefFoundError`/split class identity across old+new layers; (2) on Windows the live loader holds the JAR handle open, so `close()` can't release it → next load reads a stale JAR or fails — defeating the exact problem `PreviousLoaderTracker` exists to solve; a `while(true){}` script permanently leaks its loader+thread and can wedge later reloads.
**Impact:** instability / loader+thread leak / reload wedge (operational DoS); aggravated by H-bucket "no liveness bound on stop" (M4).
**Fix:** after `dispose()`, `awaitStop(timeoutMs)` with a bounded timeout before `closeAll()`; force-abandon (don't close the loader out from under) runners that don't stop in time.

### H? (open question) — SDN bundle integrity is delegated entirely to out-of-repo native code
`core/.../crypto/SdnLoader.java:92-129`, `SdnDiskBundleSource.java:108`, `runtime/SDNScriptLoader.java:88,125`.
For the SDN path, **none of the in-scope Java verifies any signature or AEAD.** The Java side hands attacker-influenceable bytes (an RPC-delivered `encrypted_jar`, or a `~/.botwithus/sdn/<pid>.sdn` disk file) straight into the JVM-injected native `jdk.internal.sdn.SdnClassLoader` constructor, then `ServiceLoader`-instantiates the result (executes it). If that native loader does **not** verify the bundle against a *pinned* SDN public key and use authenticated decryption, then anyone who can answer the RPC or drop a forged `.sdn` file gets RCE. The Java-side length parsing and reflection caching are sound (see clean list); **the decisive control lives in `NXTLibrary` native code and could not be audited here.**
**Action:** audit the native `SdnClassLoader` constructor separately — this is the single most important open security question for the SDN feature.

---

## MEDIUM

- **M1 — `requireSignature` reads default `false` (fail-open on a missing field).** `core/.../resolver/config/RepositoryConfigStore.java:163`. `seedDefaults()` writes `central` with `requireSignature:true` (`:125`), but the *reader* defaults a missing JSON field to `false`. Hand-deleting one line in `repositories.json` silently disables signing for `central`. **Fix:** default missing → `true`, or refuse to load a repo entry lacking the field.
- **M2 — SHA-1 accepted as sole integrity on `requireSignature:false` repos.** `core/.../resolver/pipeline/Resolver.java:339-378`. SHA-256 404 → SHA-1 fallback → `Resolved` with no PGP. SHA-1 is collision-broken; a malicious mirror/MITM can substitute a JAR. Gated by prod default being signed-only, so scoped to user-added unsigned repos. **Fix:** drop the SHA-1 acceptance path, or treat SHA-1 as advisory only.
- **M3 — SDN process `lockdown()` is gated on script presence.** `runtime/SDNScriptLoader.java:172-182` (`if (!scripts.isEmpty() && ...)`). The lockdown that's supposed to block any further unsigned-DLL load **never arms** for an empty-`scripts/` session, and is bypassed whenever a local script is present. An attacker who starts the host with an empty scripts dir (trivial given M5) keeps the DLL-injection guard disabled all session. **Fix:** call `lockdown()` unconditionally once the initial load decision is made (or gate on explicit config), not on `!scripts.isEmpty()`.
- **M4 — No liveness bound on script stop.** `runtime/ScriptRunner.java:146-151,314-328`. The interrupt is only observed at `Thread.sleep(delay)` and only when `delay > 0`; a script returning `0` from `onLoop()` and swallowing interrupts, or blocking inside `onLoop()`, is unstoppable and leaks its virtual thread after `stopAll()` drops the reference. **Fix:** clamp `delay==0` to ≥1ms; add an `onLoop` watchdog that flags "unresponsive"; document stop as best-effort.
- **M5 — Scripts dir resolved from a process-global property + parent-walk; local JARs loaded with no allow-list/signature.** `runtime/LocalScriptLoader.java:78-95`. Order: `-Dbotwithus.scripts.dir` → walk up to 3 parents for any `scripts/` → `~/.botwithus/scripts`. Every JAR is loaded with no hash/signature gate (contrast the SDN path). Anyone who can set JVM args/env or plant a `scripts/` in a CWD ancestor redirects the entire script source to attacker code = host-privilege RCE. **Fix:** pin to one non-walked root; consider a manifest/hash allow-list for local JARs; at minimum WARN-log the resolved absolute path when it came from the property or a parent-walk.
- **M6 — `NXTCache.readAndFree`/`readFileRaw` deref a native-returned pointer with no NULL guard.** `core/.../cache/NXTCache.java:387-396,355-361`. On `NXT_OK` it does `outPtr.get(ADDRESS,0).reinterpret(len).toArray(...)` with no `address()==0` check and no length sanity — a native contract violation (`NXT_OK` + NULL/garbage len) → SIGSEGV that kills the JVM (real fault, not a Panama exception). `WorldWalker.decodePath` (`:445-448`) already added exactly this guard; the cache path didn't. **Fix:** mirror the WorldWalker guard (throw on null; cap `len`).
- **M7 — `lastError()` reads an unbounded thread-local C string.** `WorldWalker.java:477-487`, `NXTCache.java:408-418` use `reinterpret(Long.MAX_VALUE).getString(0)`. The thread-locality is handled correctly (read on the calling thread inside the catch), but a non-NUL-terminated buffer makes `getString` scan until it faults. **Fix:** `reinterpret` to a sane cap (e.g. 4 KiB) before `getString`.
- **M8 — Event-pump `poll()` loop is bounded by producer-supplied `head`, no per-call cap.** `shm/EventRingReader.java:100-136`. A producer that advances `head` (and commits matching slot seqs) by a large delta keeps the pump thread busy and floods `EventBus` subscribers (CPU DoS). **Fix:** clamp `head` to `nextSeq + EVENT_RING_SLOTS` per poll; account the remainder as drops.
- **M9 — `ScriptContextChannel.enqueue` overflow handling is racy.** `impl/ScriptContextChannel.java:89-98`. The `pollFirst()` + second `offerLast()` isn't atomic under concurrent publishers → can drop without accounting or transiently overshoot the cap. Host-internal (scripts publishing their own context), so low blast radius. **Fix:** `while (!queue.offerLast(d)) { if (queue.pollFirst()!=null) dropped.incrementAndGet(); }`.

---

## LOW

- **L1 — Basic-auth `Authorization` forwarded across cross-host redirects.** `transport/HttpTransport.java:54-55,88` (and `search/SearchService.java:94`) use `Redirect.NORMAL`, which blocks HTTPS→HTTP downgrade but does **not** strip the credential on a cross-host HTTPS→HTTPS 302. A malicious configured repo can redirect to an attacker host and harvest the credential. **Fix:** follow redirects manually and re-apply `Authorization` only on a host match.
- **L2 — Plaintext `http://` repositories permitted.** `transport/HttpTransport.java`, `config/RepositoryConfigStore.java:171` (no scheme check). Basic-auth then travels base64-cleartext and, for `requireSignature:false`, integrity rests on a checksum fetched over the same cleartext channel (MITM can rewrite both). **Fix:** reject `http://`, or at least refuse to attach `Authorization` to non-HTTPS.
- **L3 — `decodePath` trusts native `stepCount` up to `Integer.MAX_VALUE`.** `worldwalker/WorldWalker.java:434-451`. Null-pointer case is guarded; an in-range-but-garbage ~2B count is not → multi-GB view + loop reading past the real buffer before faulting (JVM crash on native misbehavior). **Fix:** clamp to a sane step ceiling and throw.
- **L4 — ISC: forgeable `sender` + no isolation/quotas/bounds** *(merged: IPC + CLI/API + runtime)*. `api/.../isc/MessageBus.java:35,46,56`, `impl/MessageBusImpl.java:36-72`, `impl/SharedStateImpl.java`. `sender` is stamped from the caller-supplied string (any script can impersonate another); `MessageBus`/`SharedState` are shared by reference with no per-script namespacing; `publish/request` spawn an unbounded virtual thread per subscriber per message (flood); `SharedState` is an unbounded map any script can read/clobber/`clear`; a `Long.MAX_VALUE` request timeout leaks a `pendingRequests` entry. In-scope per the hostile-co-resident-script threat model, but a script could reach the same data reflectively anyway, hence Low. **Fix:** stamp `sender` from the runtime's known identity at the impl boundary; bound queues/threads/state size; clamp request timeouts.
- **L5 — `MessagePackCodec.decode` has no recursion-depth bound.** `msgpack/MessagePackCodec.java:35-105`. A deeply-nested msgpack doc (within the 16 MB frame) overflows the stack in `valueToObject`; `StackOverflowError` is an `Error`, so it **escapes** the `catch (Exception)` and kills the RPC thread. **Fix:** depth limit → `MessagePackException`; or catch `StackOverflowError` at the decode boundary.
- **L6 — `RpcClient` id correlation can mismatch after timeout+reconnect+id-wrap.** `rpc/RpcClient.java:294-370,406-415`. Id filtering is correct, but `idCounter` is a 32-bit `AtomicInteger` with no "no-longer-awaited" set; after wrap/reconnect a stale buffered response could match a fresh id (wrong result to wrong caller). Theoretical at normal rates. **Fix:** track outstanding ids and drop unawaited responses; widen to `long`/rebase on reconnect.
- **L7 — `PipeClient` 16 MB per-frame cap; aggregate unbounded.** `pipe/PipeClient.java:166-186`. Per-message cap is correct (`length<=0` rejects negative), but 16 MB is large for tiny RPC maps; a hostile producer can force repeated 16 MB allocations. **Fix:** lower the RPC frame cap (≈1–2 MB).
- **L8 — `StreamPipeReader` 8 MB per-frame allocation in a tight loop.** `pipe/StreamPipeReader.java:64-72`. Same aggregate-allocation shape; self-limiting per frame. **Fix:** add a frame-rate/total-bytes throttle if the stream pipe is in the attacker-can-open threat model.
- **L9 — Console echoes typed commands, incl. `scripts repo login --password <pw>`, into the in-memory log buffer/scrollback.** `cli/.../gui/ConsolePanel.java:255`, `command/impl/ScriptsResolverDispatcher.java:283-291`. Not written to disk (no FileAppender), so the secret stays in process memory only. **Fix:** redact `--password`/`--token` before echo; prefer a no-echo prompt over a flag.
- **L10 — `scripts adopt <name>` bypasses the `MavenCoord` traversal guard.** `command/impl/ScriptsResolverDispatcher.java:309-317` → `resolver/install/ScriptInstaller.java:187-213`. `scriptsDir.resolve(jarFileName)` with no `normalize()`/`startsWith` containment — `..\..\x.jar` lets the installer read+checksum+index a JAR outside `scripts/` (read/index only, operator trusted). **Fix:** `resolve().normalize()` + reject if not `startsWith(scriptsDir)`, mirroring `ScreenshotCommand.resolveOutputPath`.
- **L11 — Log connection-tag is forgeable** *(CLI/API + runtime)*. `runtime/ConnectionContext.java:29` (`public static set`), read by `cli/.../log/LogCapture.java:107`. A script can retag its own output as another connection (mislabels logs only — nothing reads the tag for authorization; log lines are not newline-injectable; the buffer is a bounded ring). Effectively Info given existing reflective reach. **Fix (only if isolation tightens):** make `set` package-private to `core` / gate behind a capability. **Flag:** do not start trusting this tag for access control.
- **L12 — SDN `lockdown()` failure is swallowed; scripts still run.** `runtime/SDNScriptLoader.java:178-180`.
- **L13 — Script crash invisible on default-wiring constructors.** `runtime/ScriptRunner.java:121-136` default `eventSink` to `e->{}`/`NOOP`; a crash updates `health()` but emits no `ScriptCrashedEvent`. Production goes through `ScriptRuntime.registerScript` (real sink), so this mainly hits test/legacy seams. Containment itself is good (one script's crash can't take down host/siblings).
- **L14 — `ManagementScriptRunner` has weaker telemetry than the lower-privilege `ScriptRunner`.** `runtime/ManagementScriptRunner.java:205-214` — no `ScriptHealth`/`ScriptCrashedEvent`/publisher on the *more* privileged (cross-client) runner. Not an isolation break; observability asymmetry.

---

## Info / by-design / corrected

- **Scripts are unsandboxed, fully-trusted local code** (no `SecurityManager` in Java 25; child `ModuleLayer` gives class-identity separation, not confinement). By design, single-operator. "A script can do X to the host/itself" is not a finding.
- **CORRECTED — producer-supplied stream `pipe_name` is NOT an arbitrary-file/UNC vector.** The `sec-cli-api` auditor flagged this as Medium on the premise that a value "beginning with `\\`" is used unmodified. Verified false: `StreamPipeReader.java:23,34` checks `startsWith(PIPE_PREFIX)` where `PIPE_PREFIX = "\\.\pipe\"` (the full prefix). A bare UNC (`\\evilhost\share`) fails that check and gets the prefix *prepended* (`\\.\pipe\\\evilhost\...`), which Java normalizes back into the **local** pipe namespace — no SMB/UNC connection, no NTLM leak. Residual: the producer can redirect the reader to an arbitrary *local* named pipe it also controls — negligible escalation (the agent already feeds all game state). **Downgraded to Info.** Optional hardening: still reject `\`/`/`/`..` in the bare-name branch for defense in depth.
- **PGP trust keyed by 64-bit key-ID, not full fingerprint** (`BouncyCastlePgpVerifier.java:94-104`, `KeyRingStore.java:166`). Short-key-id collision is theoretical; prefer `getFingerprint()`.
- **SDN disk source has no directory-permission enforcement** on `~/.botwithus/sdn/` — any local process can drop a forged `.sdn`, relying entirely on native verify (see the SDN open question).
- **Subkey-signed artifacts are rejected** (trust set stores master key IDs only) — overly strict but fail-closed, not exploitable.

---

## Verified clean (high-value confirmations)

- **Install pipeline is fail-CLOSED and correctly ordered** — JAR fetched to a staging dir, verified there, moved to `scripts/` only after checksum **and** signature pass (`ScriptInstaller.java:71-100`, `Resolver.java:256-257`). No TOCTOU; missing checksum/sig → hard fail, not skip.
- **PGP gate is verify **and** trust** — signature checked over the full JAR byte stream AND `trustedKeyIds().contains(keyId)`; empty trust set → all installs fail. No verify≠trust bug.
- **Checksums** use constant-time `MessageDigest.isEqual`, full-length-enforced, correct algorithms; `parseHex` rejects bad input.
- **XXE hardened** — `MavenMetadataParser.java:44-45` disables DTDs/external entities before parsing attacker-controlled metadata.
- **No Java deserialization anywhere** (no `ObjectInputStream`/`readObject`/`XMLDecoder`/YAML); untrusted files are Gson-into-concrete-DTO or `java.util.Properties` only — no gadget surface.
- **No command injection** — only `ProcessBuilder` is `screenshot --open` with array args on a path sandboxed by `resolve().normalize()`+`startsWith`.
- **Config/profile path traversal neutralized** — `ScriptConfigStore.safeName`/`ScriptProfileStore.sanitize` allow-list `[A-Za-z0-9_-]`, collapsing `..`/separators.
- **Credentials at rest** — `~/.botwithus/credentials.json` written atomically then 0600 / owner-only ACL; never logged; no hardcoded secrets anywhere in `cli`/`api`/`quest-core`/examples.
- **Gradle wrapper integrity** — `distributionSha256Sum` present + `validateDistributionUrl=true`; the auditor confirmed it matches the official `gradle-9.5.1-bin.zip.sha256`. Build repos are HTTPS-only (`mavenCentral`/`gradlePluginPortal`); no `allowInsecureProtocol`.
- **Snapshot readers are bounds-safe** — every `*Count` clamps `Math.min(n, CAP)`; every `*At(i)` re-validates `i`; index math uses `(long)i*SIZE` (no int overflow); event-body decoders clamp against fixed `EVENT_BODY_MAX`, not the wire length. Unknown event types → null, skipped.
- **Pipe single-threaded contract upheld** — `RpcClient` serializes all pipe access under `pipeLock`; watchdog/`settled` CAS race correctly arbitrated; `RpcTimeoutException` not retried (no retry storm); reconnect bounded.
- **FFI struct layouts verified byte-for-byte** against both C headers with class-load size asserts; upcall stubs all catch `Throwable` (no exception crosses a native frame); capability-array lifetime is run-scoped (no UAF); enum/int marshaling can't `ArrayIndexOOB`; `close()` drains in-flight queries (no UAF on close).
- **Cross-client boundary intact** — a `BotScript` only ever gets a `ScriptContext`, which exposes no `ClientProvider`/`ClientOrchestrator`; no downcast path to `ManagementContext`. A normal script cannot reach cross-client visibility.

---

## Prioritized remediation

1. **Audit the native `jdk.internal.sdn.SdnClassLoader` in `NXTLibrary`** — does the SDN bundle path fail closed? (open question above; decides whether the SDN feature is sound).
2. **H4 + H5** — add producer-wire geometry/`slotMask` validation in `SharedRegion.probeHeader` and `EventRingReader`'s ctor (same discipline as `PROTOCOL_VERSION`). Cheap, closes the two confirmed High DoS vectors.
3. **H2** — add PGP key revocation/expiry checks (small, high-value crypto correctness).
4. **H6 + M4** — make script stop enforceable (`awaitStop` before `closeAll`; clamp `delay==0`; onLoop watchdog).
5. **H1** — upgrade BouncyCastle to 1.84 (needs the documented module-info re-derivation).
6. **M1/M2/M3** — fail-closed signing default; drop SHA-1 acceptance; make SDN `lockdown()` unconditional.
7. **H3** — if not accepting the risk, add DLL signature/hash verification + canonicalized root + constrained dependent-DLL search before `libraryLookup`.
8. **M6/M7** — bring the cache FFI path up to WorldWalker's null/length defensiveness; bound `getString`.
9. Lows as cleanup (msgpack depth bound, alloc caps, password redaction, `adopt` traversal guard, ISC `sender`/quotas).

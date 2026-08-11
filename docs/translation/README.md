# On-device translation: the model plumbing (#42)

English benti → Gurmukhi, fully offline: IndicTrans2 en-indic dist-200M, int8
ONNX, greedy decode. This is the infrastructure layer only — **nothing here is
reachable by a user.** The shipping UI is #44; the feature as a whole is #35.

Source lives in `Ardaas/Translation/`.

## Public API

`BentiTranslationService` (`@MainActor`, `ObservableObject`) is the entire
surface #44 is expected to touch:

```swift
@Published private(set) var state: ModelState          // the only status surface
@Published private(set) var installedBytes: Int64      // real bytes on disk

static let downloadBytes: Int64        // exact, 358,521,397 B
static let peakDiskBytes: Int64        // ~1.03 GB, needed only during install
static let installedDiskBytes: Int64   // ~677 MB steady state

func download(allowingCellular: Bool = false)
func cancelDownload()
func deleteModel() async
func translate(_ english: String) async throws -> String
```

`ModelState` is `.notDownloaded` / `.downloading(progress:)` / `.optimizing` /
`.ready` / `.failed(TranslationError)`, and is `Equatable`.

Rules the layer keeps:

- **Nothing downloads until `download()` is called.** There is no fetch-on-first-
  translate path; `translate` before `.ready` throws `.notReady`.
- **Cellular is off by default.** `allowsCellularAccess`, plus the `expensive`
  (hotspot) and `constrained` (Low Data Mode) flags, are all set from the single
  `allowingCellular` argument. With it false the transfer fails with
  `.cellularNotAllowed` rather than quietly spending ~359 MB of data plan.
- **Every artifact is SHA-256 verified** against a pinned hash before it is moved
  into place, and a mismatch fails loudly — no silent retry, no partial install.
- **Every failure is a `TranslationError`.** No `fatalError`, no force-unwraps on
  a user-reachable path.

## Release-build gating

`project.yml` — the default spec, what CI builds and what the TestFlight
workflow archives — declares **no `onnxruntime` package and no dependency on
it.** No release build carries the ONNX Runtime xcframework today.

`project-onnx.yml` is an XcodeGen overlay that adds the package, the dependency
and `SWIFT_ACTIVE_COMPILATION_CONDITIONS = BENTI_ONNX`:

```sh
xcodegen                            # stock: no ONNX Runtime anywhere
xcodegen --spec project-onnx.yml    # engine linked, BENTI_ONNX defined
```

Both write `Ardaas.xcodeproj`, so flipping the feature on is one command, not a
code change.

### Why an overlay spec

Neither Xcode nor XcodeGen can make a Swift Package product dependency
conditional on the build configuration. XcodeGen's `Dependency` object exposes
`link`, `embed`, `weak`, `codeSign`, `platforms` and `destinationFilters` — no
configuration filter — and Xcode's own model attaches package product
dependencies to the target, not to a configuration. So "Debug with ONNX,
Release without" is not expressible on a single target.

Alternatives considered:

| Option | Verdict |
|---|---|
| Per-configuration dependency | **Not expressible.** No configuration filter in XcodeGen's Dependency object; no per-config package product dependency in the Xcode project model. |
| Separate `BentiEngine.framework` target | Doesn't solve it. Target dependencies are also unconditional, so the app would still embed the framework — and the framework in release. |
| Second app target (a lab-only app) | Solves release bloat but strands the feature: #44's UI could never reach the engine from the shipping app. |
| `EXCLUDED_SOURCE_FILE_NAMES` per config | Excludes *sources* per configuration but not *linkage*; the xcframework would still be embedded. |
| **Overlay spec + compilation condition** | **Chosen.** Truly zero ONNX in the default build, one command to flip, and the flag is a build-time constant so the dead branch costs nothing at runtime. |

The tradeoff: the two specs can drift, and the `BENTI_ONNX` code is not compiled
by the default CI job. Mitigations:

- CI runs `xcodegen generate --spec project-onnx.yml` on every PR, so the
  overlay cannot rot into an unparseable state unnoticed.
- Only session creation and inference are behind the flag — two files,
  `BentiTranslator.swift` and `OnnxGraphOptimizer.swift`. Everything else in
  `Ardaas/Translation/` is plain Foundation, compiled and unit-tested on every
  CI run in the shipping configuration.
- The ONNX-linked code is the spike's, which is green on PR #37's CI and has
  been run on device.

When #44 turns the feature on, the fix is to make `project-onnx.yml` the default
spec in `ci.yml` and `deploy-testflight.yml`, at which point CI covers both the
flag and the linkage.

## What's in each file

| file | role | ONNX? |
|---|---|---|
| `ModelCatalog.swift` | the pinned artifact set: names, exact sizes, SHA-256s, size disclosure, revision digest | no |
| `TranslationError.swift` | every typed failure | no |
| `ModelState.swift` | the five-case lifecycle | no |
| `ModelFileFetcher.swift` | `ModelFileFetching` + the URLSession implementation (progress, cancel, cellular guard, resume tokens) | no |
| `ModelInstaller.swift` | download → verify → optimize → drop originals; disk accounting; delete | no |
| `ModelOptimizer.swift` | the cache: manifest, validation, build, source-graph retirement | no |
| `OnnxGraphOptimizer.swift` | the ORT graph rewrite and the load-back check | **yes** |
| `TranslationEngine.swift` | actor: memory pre-flight, translator lifetime, release-on-pressure | no |
| `BentiTranslationService.swift` | the public `@MainActor` service | no |
| `BentiPreprocessor.swift` | IndicProcessor + sacremoses (Moses subset) port | no |
| `BentiTokenizer.swift` | source tokenizer from `tokenizer_src.json` | no |
| `BentiPostprocessor.swift` | decode, placeholder restore, Devanagari→Gurmukhi, indic detokenize, purity gate | no |
| `BentiTranslator.swift` | 3-session greedy decode with KV-cache feedback | **yes** |
| `MemoryProbe.swift` | `phys_footprint` sampling + `MemoryGuard` pre-flight | no |
| `TranslationBuild.swift` | the single `#if BENTI_ONNX` switch point | no |
| `BentiLabView.swift` | DEBUG-only diagnostics screen | no |

## Disk

| stage | on disk |
|---|---|
| Downloaded | 358.5 MB (3 graphs 351.8 MB + 6.8 MB tokenizer/dict) |
| While optimizing | ~1.03 GB (download + the ~670 MB cache) |
| **Steady state** | **~677 MB** (cache + tokenizer/dict) |

The spike kept both forever. `ModelOptimizer.retireSourceGraphs` now deletes the
`.onnx` originals — but only after proving the cache does not need them: the
originals are *moved aside*, every optimized graph is opened with them absent
(`OnnxGraphOptimizer.verifyLoadable` creates a session and touches its IO
metadata, which forces ORT past lazy initialization), and only then are they
dropped. Any failure puts them back and throws, leaving a working-but-fat
install rather than a broken thin one.

`ModelInstaller` also refuses to start when the volume reports less free space
than the install needs, so the failure is a clear message rather than a
half-written 600 MB cache.

`deleteModel()` removes the whole directory — downloads, cache, abandoned
scratch, unfinished retirement — and returns to `.notDownloaded`.

## Memory

Full analysis in [MEMORY.md](MEMORY.md). Headlines: **582 MB peak
`phys_footprint` on an iPhone 15 Pro** (from 1374 MB), ~1.9 s/sentence, via
sequential session lifecycle + detached tensors + memory-mapped external
initializers + prepacked initializers.

Guards in this layer:

- `MemoryGuard.check` reads `os_proc_available_memory()` before any session is
  created and throws `.insufficientMemory` below 800 MB of headroom.
- `TranslationEngine` is an actor, so two translations can never put two peaks
  on the ledger at once.
- Sessions are created per stage and released at the end of that stage, by
  construction — nothing is held between translations.
- A memory warning drops the resident translator (tokenizer + dictionary, ~7 MB;
  ~200 ms to reload).

## Test coverage

Everything except session creation and inference is covered on every CI run, on
the simulator, without the 359 MB artifact:

- `BentiTokenizerTests` — 21 fixture sentences (`Fixtures/tokenizer_parity.json`,
  generated by the real Python pipeline: IndicTransToolkit 1.1.1 + the reference
  tokenizer) asserted id-for-id, plus a spec-hash check on the bundled
  `Fixtures/tokenizer_src.json` and the source-length guard.
- `TransliterationTests` — transliteration (incl. danda preservation), indic
  detokenization, HF cleanup, placeholder round trip, purity gate; expected
  values generated with indic_nlp_library.
- `ModelOptimizerTests` — the cache must fail closed (missing manifest, missing
  graph, missing or truncated side-car, recipe/ORT/catalog-revision bump), plus
  source-graph retirement: it refuses without a valid cache, it is idempotent,
  and a failed load-back check restores the originals.
- `ModelInstallerTests` — the download state machine end to end against a fake
  fetcher: progress ordering, hash-mismatch failure, cellular refusal,
  cancellation, resume-vs-restart, disk-space refusal, `deleteModel` returning
  to `.notDownloaded`.
- `MemoryGuardTests` — the pre-flight refusal, including the unmeasurable case.

**Not covered in CI**, and can't be without the artifact and real hardware:

- Actual ONNX session creation, inference and output correctness. Graph-rewrite
  parity (original vs external-initializer vs prepacked-external graphs) was
  verified off-device against all 21 fixtures — identical token ids in all 63
  decode runs; see MEMORY.md.
- The real memory numbers. `MemoryProbe` is exercised (it reports a footprint,
  the sampler is idempotent) but the 582 MB figure comes from a cable run.
- Real network behaviour: HTTP status handling, resume tokens produced by
  URLSession, the cellular guard actually failing with `NSURLErrorDataNotAllowed`.
  The *handling* of those outcomes is tested through the fake fetcher; the
  transport producing them is not.
- Compilation of the two `BENTI_ONNX` files (see "Release-build gating" above).

Fixture regeneration: `indictrans-spike/gen_fixtures.py` in the spike workspace
(needs its venv).

## Known approximations

- Perl `IsAlnum`/`IsAlpha` classes approximated with ICU `\p{L}\p{M}\p{N}`
  (differences unreachable for English input; locked by fixtures).
- No sentence splitting: one benti is one model call, hard-capped at 256 source
  positions (≈150 English words). Longer input is refused with `.inputTooLong`
  rather than silently truncated.
- Restored placeholders (dates, numbers) legitimately trip the Gurmukhi purity
  gate, so it is advisory — #44 shows it as a caution on the draft rather than
  refusing the translation.
- Resume tokens are in-memory only. An app kill restarts the file that was in
  flight; whole files that already landed are never re-fetched, so the worst
  case is one file, not 359 MB.
- Installed files are matched by exact expected size on launch, not by re-hashing
  ~677 MB. The hash gate is at download time, before anything is moved into
  place.

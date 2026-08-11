# On-device translation: memory (#35)

The translation spike worked but peaked at **1374 MB** of `phys_footprint` on an
iPhone 15 Pro. That is survivable on an 8 GB device and fatal on a 3–4 GB one.
Latency had headroom (1662 ms/sentence); memory did not. This pass attacked
memory only — output is bit-identical.

**Result: 1374 MB → 582 MB, measured on an iPhone 15 Pro**, at ~1.9 s/sentence.
Session creation got ~4.8x *faster*. The host predicted ~555 MB and the device
came in at 582 MB (+5%), which is the expected direction and magnitude.

All numbers below were measured on the Linux reference host (16-core Xeon-class
CPU, ORT 1.28 CPU EP, batch 1, greedy, 41-token source, 40 decode steps) with
`indictrans-spike/mem_probe.py`. The host reproduces the device baseline closely
(1331 MB vs 1374 MB, +3%), so relative effects should transfer; absolute iOS
numbers still need a cable run.

---

## Where the 1374 MB actually was

Three things, none of them obvious from the file sizes:

### 1. A 251 MB fp32 tensor materialised on every decode step

`lm_head.weight_quantized` is a `[122672, 512]` **uint8** initializer (62.8 MB).
The quantizer left the output projection as *weight-only* quantization:

```
lm_head.weight_quantized --Transpose--> val_2256_quantized
                         --DequantizeLinear--> val_2256  [512, 122672] fp32 = 251 MB
                         --MatMul(hidden, val_2256)--> logits
```

ORT deliberately does **not** constant-fold `DequantizeLinear` (folding it would
undo the quantization), so this 251 MB fp32 tensor is rebuilt on *every single
decode step*, in both `decoder_model` and `decoder_with_past_model`. It is the
floor under everything else here, and it is why the arena behaves as it does.

### 2. The BFC arena keeps two copies of it

The CPU arena never shrinks and, once fragmented, extends rather than reuses.
Per-step RSS shows it settling at exactly two 251 MB regions by step 2 and then
staying flat:

```
after session   546 MB
step 0          794 MB   (+248, 136 ms)
step 1          805 MB   (40 ms)
step 2         1041 MB   (+237, 129 ms)   <- second region
step 3..39     1041 MB   (33-34 ms)
```

### 3. ~350 MB of weights on the heap, all three graphs at once

`encoder` (76 MB) + `decoder_model` (143 MB) + `decoder_with_past` (133 MB) were
all created up front and held forever, even though the encoder is only needed
for step 1 and `decoder_model` only for the *first* decode step. Initializers
stored inside the .onnx protobuf are read onto the heap: anonymous, dirty pages,
every byte charged to `phys_footprint` and therefore to jetsam.

---

## What landed

Cumulative, each row adding to the one above. "Peak" is peak anonymous memory,
the closest host analogue to iOS `phys_footprint`.

| # | Lever | Peak | Decode (40 steps) |
|---|---|---|---|
| 0 | Baseline | 1331 MB | 1556 ms |
| 1 | Sequential session lifecycle | 1037 MB | 1550 ms |
| 2 | Copy tensors out of ORT-owned memory | 752 MB | 1639 ms |
| 3 | Memory-mapped weights (external initializers) | 602 MB | 1598 ms |
| 4 | Pre-packed initializers mapped too | **537 MB** | 1734 ms |

Scaled by the host↔device ratio this predicted ~555 MB on an iPhone 15 Pro;
the cable run measured **582 MB**.

### 1. Sequential session lifecycle (−294 MB)

`BentiTranslator` creates each session immediately before it is needed and
drops it immediately after: encoder → release → `decoder_model` → release →
`decoder_with_past` → release. Nothing is held between translations, so peak no
longer accumulates across sentences and idle footprint returns to baseline.

The reload cost is what makes this cheap rather than expensive — see lever 3;
creating all three sessions from the mapped graphs costs ~204 ms total, against
~976 ms from the original files.

### 2. Copy tensors out of ORT-owned memory (−285 MB)

`ORTValue.tensorData()` hands back the session's *own* buffer, so anything that
outlives its producing session must be copied. That was a correctness
requirement of lever 1 — and it turned out to be a large memory win on its own.

Holding the 72 step-1 `present.*` tensors as ORT-owned values pinned the entire
251 MB arena region they were allocated from, for the whole decode loop.
Copying them into caller-owned `NSMutableData` (a few MB) releases it. Measured
cost: none.

`detachIfNeeded` also runs inside the loop, and each decode step now runs in its
own `autoreleasepool`.

### 3. Memory-mapped weights (−150 MB)

The single biggest lever reachable through the ONNX Runtime Objective-C API,
which is far narrower than the Python one — it exposes no arena controls at all
(see "What didn't work").

On first load, `ModelOptimizer` creates a throwaway session per graph with
`optimized_model_filepath` plus
`session.optimized_model_external_initializers_file_name`, and ORT writes an
optimized graph whose initializers live in a side-car `.data` file. On every
later load ORT memory-maps that file instead of reading it onto the heap
(`GetExtDataFromTensorProto` → `Env::MapFileIntoMemory`, the default on POSIX
including iOS).

This matters because of how the XNU ledger defines the number jetsam enforces:

```
phys_footprint = internal + internal_compressed + iokit_mapped
               + purgeable_nonvolatile + ... (no external/file-backed term)
```

Only *anonymous* pages are `internal`. Clean file-backed pages are not charged
**even when fully resident** — confirmed in `osfmk/kern/task.c` and stated
outright in WWDC18-416 ("clean memory doesn't really count"). So mapping the
weights removes them from the footprint without removing them from RAM.

Bonus: session creation drops from 976 ms to 204 ms for all three graphs,
because ORT no longer copies 352 MB off disk.

The pass is CPU-only by construction. Registering CoreML (or any EP producing
compiled nodes) makes ORT refuse to serialize with *"Unable to serialize model
as it contains compiled nodes"*. This spike appends no execution provider, so
this is simply the default — but it is a trap for anyone who later adds one.

### 4. Pre-packed initializers mapped too (−65 MB)

Prepacking rearranges weights into kernel-friendly layouts, and those buffers
are normally allocated on the **heap** at session creation — quietly undoing
part of lever 3. `session.save_external_prepacked_constant_initializers` writes
them into the same external file so they are mapped as well.

The alternative, `session.disable_prepacking`, reaches the same 537 MB but costs
~26% decode latency. Prepacked-external is strictly better on both axes; it just
costs disk (see "Costs").

---

## What didn't work

| Attempt | Result | Why |
|---|---|---|
| `enable_cpu_mem_arena = false` | 527 MB but **4× slower** (1.55 s → 6.3 s) | Kills both 251 MB arena regions, but the 251 MB dequant buffer is then faulted in fresh every step. ~120 ms/step of page-fault zeroing. Also **not reachable from the ObjC API**. |
| `arena.extend_strategy = kSameAsRequested` | no change (1040 MB) | Not a real session-config key; the arena extend strategy is only settable through `OrtArenaCfg`. |
| `enable_mem_pattern = false` | no change (1045 MB) | The second arena region is BFC fragmentation, not mem-pattern pre-planning. |
| Tuned `OrtArenaCfg` `initial_chunk_size_bytes` (256/320/512 MB) | no change (785–793 MB) | Does not stop the second extension. |
| **Capped arena** `max_mem = 320–448 MB` via shared env allocator | 599–607 MB **at full speed** — genuinely promising | Forces BFC to reuse instead of extend. Below ~300 MB it hard-fails: *"Available memory of 205939968 is smaller than requested bytes of 251232256"*. **Unreachable**: `ORTSessionOptions` exposes no arena config, and a shared env allocator also breaks sequential release (the arena outlives the session — measured idle footprint 420 MB vs 153 MB). |
| `RunOptions memory.enable_memory_arena_shrinkage` per step | 808 MB and 5.75 s | Returns the arena between steps, so every step re-faults. Worse on both axes. |
| `session.use_device_allocator_for_initializers` | no change | Confirmed no interaction with the mmap path. |
| `graph_optimization_level = BASIC` / `DISABLE_ALL` | no change or worse | Prevents the `Transpose` constant-fold, moving 63 MB from resident to transient. |
| Requesting fewer decoder outputs | no memory change | Kept anyway — the `present.*.encoder.*` outputs are `Identity` pass-throughs and the loop feeds the step-1 tensors back unchanged, so requesting them only bought 36 needless copies per step. |
| Merged decoder (`decoder_model_merged.onnx`) | **not possible** | No such export exists in `naklitechie/indictrans2-en-indic-dist-200M-ONNX-int8`, and we have no write access to publish one. The premise was right — the two decoder graphs differ only by the cross-attention K/V projections (142.9 − 132.9 = 10.0 MB ≈ 18 layers × 2 × 512 × 512 int8 = 9.4 MB), so they duplicate ~133 MB of identical weights. Lever 1 reaches the same peak without a new artifact, since the graphs are never resident simultaneously. |

---

## The next lever (blocked on a re-export)

After mmap, essentially **all** remaining anonymous memory is the two 251 MB
arena regions: 2 × 251 + ~36 MB of app baseline = 538 MB, matching the measured
537 MB almost exactly.

Re-quantizing the `lm_head` projection so it runs as `MatMulInteger` (int8)
rather than `DequantizeLinear` → fp32 `MatMul` would remove both regions. That
is worth an estimated **~90 MB peak** — and it should be substantially *faster*
too, since the current path writes 251 MB and does an fp32 GEMM per token.

Blocked on producing and hosting a new artifact: we have no write access to the
HF repo. Doing it on-device would mean ONNX graph surgery in Swift with no
protobuf library — not viable. This is the right follow-up issue.

---

## Costs and caveats

- **Disk.** The optimized cache is ~670 MB (~639 MiB). The spike kept the
  352 MB of originals alongside it, for ~1.0 GB total; **#42 deletes them** once
  the cache has been proved to load without them, so steady state is ~677 MB
  (cache + 6.8 MB of tokenizer/dictionary). The ~1.03 GB figure is now only the
  transient peak *during* the install, and is disclosed as such before the user
  opts in. Dropping `save_external_prepacked_constant_initializers` would halve
  the cache at the cost of 65 MB of peak; peak is the scarcer resource, so it
  stays on.
- **One-time cost.** The optimize pass is ~1.7 s on the host for all three
  graphs and briefly loads each graph onto the heap, so first load peaks near
  the old numbers for a moment. It runs once, is written to a scratch directory
  and moved into place atomically, and is keyed by recipe version + ORT version
  + source file sizes, so a re-download or an ORT bump rebuilds it.
- **ORT version coupling.** An optimized graph is only guaranteed loadable by
  the ORT build that wrote it. `ModelOptimizer.ortPackageVersion` must track the
  `exactVersion` pinned in `project.yml`.
- **Host-measured, device-confirmed.** Anonymous-memory-on-Linux is a proxy for
  iOS `phys_footprint`. The mechanism is confirmed at the XNU-ledger level, and
  the cable run on an iPhone 15 Pro landed at 582 MB against a 555 MB
  prediction.

## Output parity

Non-negotiable, and verified: all **21** tokenizer parity fixtures were decoded
end to end through the original graphs, the external-initializer graphs, and the
prepacked-external graphs. Generated token id sequences were **identical in all
63 runs**, including the 122-token long case. The optimization is a pure ORT
graph rewrite; it does not touch arithmetic.

---

## Recommended minimum-device policy

Jetsam foreground limits are not published by Apple; these are the
community-measured figures (`os_proc_available_memory()` is the only reliable
runtime source, and the lab screen now shows it).

| Device RAM | Examples | Approx foreground limit | ~555 MB peak |
|---|---|---|---|
| 3 GB | iPhone SE 2/3, XR, 11 Pro | ~1.4 GB | Fits, ~2.5× headroom |
| 4 GB | iPhone 11, 12, 13, 14 | ~2.0 GB | Comfortable |
| 6 GB | 13/14 Pro, 15, 15 Plus | ~3.0 GB | Comfortable |
| 8 GB | 15 Pro, 16 Pro | ~4.0 GB | Comfortable |

**Recommendation: ship to all iOS 17 devices** (iPhone XR/XS and later, i.e.
3 GB minimum), rather than gating on RAM. At 582 MB the pipeline fits inside the
tightest limit with roughly 2.4x headroom, where the 1374 MB baseline left
essentially none.

Both guards recommended here shipped in #42:

1. `MemoryGuard.check` reads `os_proc_available_memory()` before any session is
   created and throws `.insufficientMemory` below 800 MB of headroom, rather
   than risking a jetsam kill. An unmeasurable reading (the call returns 0
   outside an app context) is treated as "don't know", not as "no memory" —
   refusing every translation on the strength of a reading we could not take
   would be worse than the risk.
2. `BentiTranslationService` observes
   `UIApplication.didReceiveMemoryWarningNotification` and calls
   `TranslationEngine.release()`. Sessions are already per-stage, so this drops
   only the tokenizer and dictionary (~7 MB), and the next translation reloads
   in ~200 ms.

The 3 GB tier should still be confirmed on real hardware (an iPhone SE 3 or 11)
before the policy is treated as settled — the 3 GB jetsam figure is inferred
from iPhone X measurements rather than directly measured.

---

## Reproducing

Host harness (needs the spike venv and the ONNX files):

```sh
cd indictrans-spike
python mem_probe.py baseline          # all sessions live, ORT-owned tensors
python mem_probe.py seq-noarena       # sequential + arena off
```

On device: Benti Lab (DEBUG builds, flask button in the Home toolbar) →
Download model → Translate. The Memory section reports peak footprint for that
translation, the delta against the 1374 MB baseline, and per-stage footprints;
the Disk section reports bytes on disk and remaining jetsam headroom.

The spike's Baseline/Optimized picker is **not** in the productized code. It
existed to measure both pipelines in one cable run, and keeping a known-fatal
1374 MB configuration alive in shipping code to make a graph look good is not a
trade worth making. The numbers it produced are the tables above.

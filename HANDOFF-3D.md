# Handoff: Nds4j 3D (NSB*) support

**Branch:** `feature/3d-formats` (off updated `main`). **Last commit:** `2719663` (F1–F4 complete: read incl. SPA, animate, view, edit, author from scratch).
**Scope of this doc:** where the Nitro-3D work stands, the next tasks, and — most importantly — the
hard-won lessons and traps so the next agent doesn't repeat them.

> **Status: #26, #27, every §10 follow-up, and the entire F1–F4 roadmap are done** — read, animate, export,
> view, edit, **author NSB* from scratch**, and read **SPA** particle archives (F3 now covers NSBMA + SPA).
> Placement composes node scale separately (the NNS renderer's rule, not a baked
> `T·R·S` matrix): multi-node **75%→~97%**. Materials are wired to TEX0 textures; a self-contained
> **glTF 2.0** exporter (`GltfExporter`, now with **animation**) and a pure-JVM **`SoftwareRenderer`**
> (now **animated** — all four tracks) both work. **Seven** NSB* formats are byte-exact and decoded:
> NSBMD, NSBTX, **NSBCA** (skeletal, `Model.pose()`), **NSBTA** (texture-SRT), **NSBTP** (pattern),
> **NSBVA** (visibility), and **NSBMA** (material-color, RE'd from files — not in the jar).
> `NitroAnimation` composes the four playable tracks per frame; `AnimatedGif` writes looping previews;
> `ModelViewer` is a headless-capable Swing/Java2D viewer (orbit, scrub, play, inspect, texture browser).
> The **writer foundation** (`G3dFile.writeBlockU8/U16`) supports byte-valid in-place edits — NSBMA
> color/alpha keyframes and NSBTX palette recolor (incl. embedded TEX0, so `ModelSet.save()` re-emits
> a valid repainted model). Camera-facing **BB/BBY billboards** render. The **encoder** is real: NNS
> Patricia dictionary builder (`G3dDictionary.build`, 5388 dicts 100% functional) + container assembler +
> **geometry encoder** (`DisplayList`, geometry-exact over 400 meshes) + **texture encoder**
> (`TextureSet.encodeTextureData`, 600 textures 100% pixel-exact) — composed into **authoring a valid
> NSBTX and a full NSBMD from scratch** (MDL0 assembly + geometry encoder), both read back by the
> production decoders. **SPA** particle archives read too (`ParticleSet`: 3144 files byte-exact, 8906
> particle sprites decoded). **225 tests green — the F1–F4 roadmap AND the entire §9 breadth list are complete.**
> The §9 follow-ups are now delivered too: **SPA emitter decode + particle previewer** (`ParticleSet.Emitter`,
> `ParticleRenderer`), an **OBJ import front-end** with **textured and multi-shape/multi-material authoring**
> (`ObjImporter`, `ModelBuilder`), a native **`.imd`→NSB\* translator byte-identical to g3dcvtr** (`ImdImporter`,
> covering **all three g3dcvtr modes** `-emdl`/`-eboth`/`-etex`, **multi-node trees with full node transforms**,
> multi-material and multi-shape; SBC matrix-stack allocator and node local matrices validated byte-for-byte,
> exposed as an enriched class-based API into the flagship `ModelSet`/`TextureSet`), an **animation writer**
> (`AnimationBuilder`, NSBTA), **MTX_SCALE** resolved as a non-gap, a **posed billboard** pivot, and a general
> **Nitro LZ10/LZ11 codec** (`NitroLz`). The **animation half of g3dcvtr** is now fully ported too:
> byte-exact **NSBVA/NSBTP/NSBTA/NSBMA/NSBCA** writers (`encode()`), each round-trip-validated over the entire
> retail corpus (all five NSB* animation types — see §9c). See §4 for the
> #26 fix, §6 for the gold-standard references, §9a for the delivered breadth list, and §10 for the roadmap.

Read this alongside `TECH_DEBT.md` (the *decided* design constraints) and the memory notes
`nds4j-3d-formats-first-class-plan` and **`nsb-gold-standard-references`** (start RE from
scurest/nsbmd_docs + Apicula — see §6). This doc is the *working* handoff; `TECH_DEBT.md` is the durable
policy.

> **Newer Nds4j work (2026-08-29) is 2D, not 3D — see §11.** The `images/` formats gained **write-back**
> APIs (`Screen.applyImage`, `CellBank.applyImage`, + palette-rebuild variants) driven by **NitroViewer**
> (the in-browser Tinke replacement — its `HANDOFF.md`). **Critical cross-cutting rule from that work:**
> CheerpJ's JRE is **Java 8**, so any Nds4j class a browser can reach must avoid **Java 9+ API calls**
> (`List.of` cost `ModelBuilder` a browser-only `NoSuchMethodError`). Full detail + the new APIs in **§11**.

---

## 0. The one rule that defines "done"

**Correctness = byte-identical round-trip** across the five retail Gen IV ROMs in the workspace root
(`Platinum.nds`, `HeartGold.nds`, `SoulSilver.nds`, `Diamond.nds`, `Pearl.nds`). Tests find them via
`-Drom.dir=<workspace-root>`. For decoded *geometry* (which is a
lossy view, not a round-trip), the correctness proxy is the **two oracles** in §3.

**And:** the library must stay **100% OS-agnostic** — pure JVM, no per-OS native binaries. This is
non-negotiable and already drove the glTF/OBJ/software-rasterizer decisions in `TECH_DEBT.md §3`. Do
not add LWJGL/JOGL or anything with native artifacts to core.

---

## 1. Current state (what works)

All in `src/main/java/io/github/turtleisaac/nds4j/g3d/`:

| Class | Role | Status |
|---|---|---|
| `G3dFile` | shared NSB* container base (block-verbatim, byte-exact `save()`, `equals`/`hashCode`) | done |
| `G3dDictionary` | NNS resource dict (patricia + named records) | done |
| `TextureSet` (NSBTX) | TEX0 decode, 7 formats → `BufferedImage`, PNG export | done |
| `ModelSet` (NSBMD) | MDL0 model dict → `List<Model>`, byte-exact container; `getEmbeddedTextures()` | done |
| `Model` | geometry + **node placement** + **materials→textures/UVs** + `pose(anim, frame)` + `localTransforms`/`getRawPositions`/`isBillboardNode` | done to ~99% |
| `GltfExporter` | `Model` (+ `TextureSet`) → self-contained **glTF 2.0**; flat static **and** hierarchical **animated** (NSBCA node channels + NSBTA `KHR_texture_transform`) | done |
| `SoftwareRenderer` | headless pure-JVM preview: bind pose, `pose()` frame, **or a `NitroAnimation.Frame`** (all four tracks); `renderFrames` for a clip | done |
| `NitroAnimation` | composes NSBCA/NSBTA/NSBTP/NSBVA into a per-frame `Frame` parallel to `getMeshes()` | done |
| `AnimatedGif` | frames → looping animated GIF, pure `javax.imageio` | done |
| `ModelViewer` / `ModelViewerFrame` | headless-capable Swing/Java2D viewer: viewport+HUD, orbit, scrub, play, inspect, texture browser | done |
| `SkeletalAnimationSet` (NSBCA) | BCA0/JNT0 → `List<Animation>` of per-node SRT tracks; byte-exact container | done |
| `TextureSrtAnimationSet` (NSBTA) | BTA0/SRT0 → per-material texture-matrix (scale/rot/trans) tracks; byte-exact | done |
| `TexturePatternAnimationSet` (NSBTP) | BTP0/PAT0 → per-material flip-book keyframes (frame→texture/palette); byte-exact | done |
| `VisibilityAnimationSet` (NSBVA) | BVA0/VIS0 → per-node on/off bit stream; byte-exact | done |
| `ParticleSet` (SPA/SPL) | " APS" particle archive → **fully-decoded emitters** (`Emitter`: spawn/velocity/life/color+scale+alpha curves/fields) + " TPS" sprite textures; byte-exact | done |
| `ParticleRenderer` | headless pure-JVM previewer that **plays** an SPA move effect (simulate emitters → additive sprite composite → deterministic clip) | done |
| `ObjImporter` | Wavefront OBJ (v/vt/f, polygons, negative indices) → flat vertex/uv/triangle arrays | done |
| `ImdImporter` | native **`.imd`→NSB\*** translator, **byte-identical to g3dcvtr** (`-emdl`/`-eboth`/`-etex`); multi-node + full node transforms + multi-material/shape; enriched class API → `ModelSet`/`TextureSet` | done |
| `ModelBuilder` | author NSBMD from arrays: untextured / textured (embedded TEX0) / **multi-shape multi-material**; auto posScale + header box | done |
| `AnimationBuilder` | author **NSBTA** (texture-SRT) from scratch (constant/keyframe channels) — the animation-writer recipe | done |
| Animation **writers** (`encode()`) | byte-exact **NSBVA/NSBTP/NSBTA/NSBMA/NSBCA** re-encoders (the animation half of g3dcvtr), each round-trip-validated over the whole retail corpus (see §9c) | done |
| `NitroLz` (framework) | general **Nitro LZ10/LZ11** codec: decompress + compress, round-trip-exact; feeds the 3D pipeline (compressed NSBMD) | done |
| `MaterialColorAnimationSet` (NSBMA) | BMA0/MAT0 → per-material color/alpha tracks (RE'd from files); byte-exact; **in-place editable** | done |
| `G3dFile` writer | `writeBlockU8/U16` → same-size in-place edits (NSBMA color/alpha, NSBTX `setPaletteColor`) | done |

**Numbers (all five ROMs, current `9c057b2`):**
- Container byte-exact: NSBMD **5482/5482**, NSBCA **825/825**, NSBTA **548/548**, NSBTP **506/506**,
  NSBVA **15/15**.
- Vertex-count oracle: **5482/5482 (100%)**.
- Placement (decoded AABB vs header box, billboard/skinning excluded via `Model.hasDynamicPose()`):
  single-node **98.8%**, multi-node static-pose **97.5%** (96.5% Platinum).
- Animation channel decode vs the reference jar: NSBCA exact (manene's 5 anims — 1156 T, 120 S, 9630 R
  samples, 0 mismatches); NSBTA exact over 112974 samples (the only 2 deltas are negative fx16 constants
  the reference mis-reads as unsigned — our signed reading is correct, confirmed against the raw bytes).

**Test suite:** 225 tests, 0 failures. New since the §9 work: `g3d/ParticleRendererTest.java`,
`g3d/ObjImportTest.java`, `g3d/AnimationBuilderTest.java`, `framework/NitroLzTest.java` (plus new cases in
`ParticleSetTest`, `ModelSetTest`, `BillboardTest`). Tests: `g3d/ModelSetTest.java`, `g3d/TextureSetTest.java`,
`g3d/GltfExporterTest.java`, `g3d/GltfAnimationTest.java`, `g3d/SkeletalAnimationSetTest.java`,
`g3d/TextureAndVisibilityAnimationTest.java`, `g3d/MaterialColorAnimationTest.java`,
`g3d/SoftwareRendererTest.java`, `g3d/AnimatedPreviewTest.java`, `g3d/ModelViewerTest.java`,
`g3d/NsbEditingTest.java`, `g3d/BillboardTest.java`, `g3d/G3dDictionaryBuildTest.java`, `g3d/DisplayListTest.java`, `g3d/TextureEncodeTest.java`, `g3d/AuthorNsbtxTest.java`, `g3d/AuthorNsbmdTest.java`, `g3d/ParticleSetTest.java`. Run:
`mvn -f Nds4j/pom.xml -Drom.dir=<workspace-root> test`.

**Naming convention (enforced, see `TECH_DEBT.md §2`):** classes are named by *domain concept*, never
by file extension. NSBTX=`TextureSet`, NSBMD=`ModelSet` (holds `Model`s), mesh=`Model.Mesh`. When you
add NSBCA etc., pick a concept name (e.g. `SkeletalAnimation`), **not** `Nsbca`.

---

## 2. How placement works now (so you can extend it safely)

Two stages in `Model`'s constructor:

1. **`parseNodeLocals(mdl0, modelStart + 0x40)`** — per node: `u16 flags`, `fx16 rotation[0][0]`,
   optional translation (`3× fx32`), rotation remainder (either a full `8× fx16` 3×3 or a
   **pivot-compressed** form), and scale (`3× fx32` + `3× fx32 inverse`). Builds a **3×4 local matrix
   = T·R·S** (`Model.java:132-168`).
2. **`walkSbc(...)`** — walks the SBC render-command stream: tracks the current node, records each
   node's parent, binds each `SHP` to the current node, then resolves **`world[n] = parent.world ·
   local[n]`** down the tree (`resolveWorld`). Each mesh's positions are then multiplied by its shape's
   node world matrix (`transformInPlace`), after the display list already applied `posScale`.

**Why parent-tree and not a real matrix stack:** the review verified (3462/3462 models) that the SBC's
restore-slot always holds exactly the node named as the `NODEDESC` parent, so the parent-tree is
*information-equivalent* to emulating the matrix stack, and it's simpler. **Do not** rewrite this as a
matrix-stack emulator — that was tried twice and regressed both times (see §5).

### The authoritative SBC operand table (verified against the reference jar)
`opcode = byte & 0x1F`, store/restore flags in the high bits (`byte & 0xE0`).

| op | name | operand bytes |
|----|------|---------------|
| 0x00 | NOP | 0 |
| 0x01 | RET | stop |
| 0x02 | NODE | 2 (nodeId, visibility) |
| 0x03 | MTX (restore) | 1 |
| 0x04 | MAT | 1 (flag bits are hints, **not** extra operands) |
| 0x05 | SHP | 1 |
| 0x06 | **NODEDESC** | **3 base** (nodeId, parentId, **opt**) + 1 if `byte&0x20` (DestIdx, store) + 1 if `byte&0x40` (SrcIdx, restore) |
| 0x07 | BB | 1 (nodeId) + same optional store/restore bytes as NODEDESC |
| 0x08 | BBY | 1 (nodeId) + same optional store/restore bytes |
| 0x09 | NODEMIX | 2 + 3×NumMtx (NumMtx at operand[1]) |
| 0x0A | CALLDL | 8 |
| 0x0B | POSSCALE | 0 |
| 0x0C | ENVMAP | 2 |
| 0x0D | PRJMAP | 2 |

Any command that consumes the wrong number of bytes **desyncs the whole stream** and typically ends by
misreading an operand as `RET` (0x01) — silent, catastrophic, and invisible to the vertex-count oracle
(see §5, the manene bug).

---

## 3. The two oracles (and their blind spots)

1. **Vertex-count oracle** (`geometryMatchesHeaderVertexCount`): emitted vertex count must equal the
   header's declared count. Strong for the *display-list interpreter*; **blind to placement** (a shape
   in the wrong place still has the right vertex count) and **blind to SBC desync** (the SBC walk
   doesn't feed vertex counts).
2. **Placement oracle** (`modelsArePlacedInHeaderBox`): decoded AABB must match the header's bounding
   box, within `max(1e-3, 2% of extent)`. This is the *only* thing that catches wrong node placement.
   It is now split into **single-node (>95%) and multi-node (>60%) floors** so a multi-node regression
   can't hide inside the ~4700 single-node models. **Keep it split.** A count-only total was exactly
   what let the manene bug ship green.

**Critical property of the placement oracle:** it compares **bind-pose vs bind-pose**. The header box
is authored from the model's own bind pose; our decode is bind pose. **Animation (NSBCA) cannot change
either side.** So any AABB miss is a *bind-pose decode* gap — never "we're missing the animation." Do
not go chasing animation to fix an AABB number (see §5).

---

## 4. Tasks #26 and #27 — DONE (what the fix actually was)

### Task #26 — hierarchical scale (multi-node 75%→96%, overall 99%) ✅
The handoff **hypothesis** (use the discarded inverse scale + node-flag high bits for
segment-scale-compensate) was **wrong** — the reference `nitroreader.nsbmd.Node` discards the inverse
scale too, and never reads SSC flag bits. The real mechanism (from disassembling `renderer.TransfMatrix`
+ `gpucommands.VTX`): the NNS renderer **never bakes scale into a matrix**. It keeps translation, per-axis
scale and rotation **separate** per node, accumulates scale as its own factor, and applies
`scale → rotate → posScale → translate` per vertex. Baking `T·R·S` and multiplying down the tree (what we
did) let a parent's scale *shear* a scaled child's rotated geometry — that was the entire multi-node-scale
gap. Fix: `Model.Srt` (t/s/r kept apart), `compose()` = `renderer.TransfMatrix.applyParentTransf`
(`s*=parent.s`, `t=parent.t + (t*parent.s)*parent.r`, `r=child.r*parent.r`, row-vector), and
`transformInPlace` applies `((v⊙s)·r)*posScale + t`. Commit `b1d15a9`. Placement floor raised 60%→90%.

**Still open (small):** `MTX_SCALE` (display-list op `0x1B`) is still skipped (`Model.java`, `pos += 12`)
— a handful of `g_demo_*` effect models. Low population, vertex-count oracle is still 100%; folded into
§10.

### Task #27 — the appearance layer ✅
1. **Textures/materials → UVs → glTF.** MDL0 material set parsed (`parseMaterials`): the material dict +
   the tex/pltt→material dicts name each material's texture/palette. The SBC walk binds the `MAT` material
   per `SHP`. Texcoords stay in **texel units** (the material's size field is 0; the renderer takes size
   from the bound TEX0 texture). `ModelSet.getEmbeddedTextures()` exposes the embedded TEX0.
   `GltfExporter` writes a self-contained glTF 2.0 (geometry base64 buffer + PNG textures as data URIs,
   DS wrap/flip → sampler modes, alpha-test → `MASK`). Commits `57fd902`, `d46ad90`.
2. **NSBCA skeletal animation.** `SkeletalAnimationSet` (BCA0/JNT0): per-node scale/rot/trans tracks
   (identity / base / const / variable; keyframes every 1/2/4 frames + linear interp; rotations = u16
   indices into pivot-6-byte / 5-value-10-byte pools, 3rd row = cross product). `Model.pose(anim, frame)`
   re-poses the bind-pose skeleton. Decode matches the reference jar exactly (0 mismatches on manene's 5
   anims); round-trip 825/825. Commit `b021133`.

---

## 5. Mistakes already made — do not repeat

1. **The manene bug (the big one): a wrong SBC operand width silently desyncs everything.** `NODEDESC`
   was parsed as base-2 with the store/restore flags **swapped** and the mandatory `opt` byte
   **missing**. After two NODEDESCs the walk landed on an operand, misread `0x01` as `RET`, stopped
   with **zero SHP bindings**, and every shape collapsed onto node 0. The vertex-count oracle stayed
   100% and the old count-only placement test stayed green. **Lesson:** get *every* operand width from
   the reference bytecode, not from docs or guesses; and any per-command test must be able to localize
   *which* node-count bucket broke.

2. **Do not "fix" an AABB miss with animation.** The placement oracle is bind-pose vs bind-pose;
   animation touches neither. An earlier instinct to attribute manene's collapsed limbs to a missing
   animation layer was wrong — it was a bind-pose SBC desync. (The animation layer *is* real and
   needed for the *photo*, but that's task #27, not a placement fix.)

3. **Do not rewrite the parent-tree as a matrix-stack emulator.** Tried twice; regressed both times
   (numNode=3 went 51%→34%, crashed ~26 models) because NODEDESC store/restore timing is subtle. The
   parent-tree is provably equivalent here (§2). If you *think* you need the stack, you probably have
   an operand-width bug instead — check §2's table first.

4. **A green suite proved nothing about placement until the oracle was split.** The count-only,
   Platinum-only threshold (`>750`) was dominated by single-node models and hid a total multi-node
   collapse. Keep the single/multi split, and prefer bucketed measurement when investigating.

5. **Adversarial review is load-bearing but not infallible.** The review correctly found two
   operand-size bugs (`MAT`, `BB`/`BBY`) and the oracle blind spot, but *missed* the dominant
   `NODEDESC` bug and mis-attributed the residual to SSC. **Always confirm a review's diagnosis against
   the actual failing bytes** (the manene trace is what found the real bug). Trust the ROM, not the
   summary.

6. **Shell `cwd` resets to `Nds4j/` between Bash calls; relative ROM paths and `readlink` bite you.**
   Use **absolute paths** for ROMs and the jar, or `cd <workspace-root> &&
   ...` in the same command. A "broken symlink" report earlier was actually a relative-path artifact.

7. **Do not edit files in `/tmp` with `sed -i`/in-place python for RE prototyping** — it corrupted a
   pivot prototype (with/without-transform counts got swapped). Edit the real library file directly and
   lean on the oracle to measure.

8. **The handoff's own #26 hypothesis (inverse scale + SSC flag bits) was a red herring.** The reference
   discards both. The fix was the compose *math* (separate scale), not extra parsed fields. **Lesson
   (again, §5.5): the reference bytecode is ground truth over any prose theory — including this doc's.**
   Before implementing a hypothesised mechanism, disassemble the reference path that consumes the data
   (`renderer.TransfMatrix` / `gpucommands.VTX` here) and copy what it *does*, not what a comment says.

9. **The SPA "N/A" blunder: a one-directional magic scan wrongly declared a whole format absent.** SPA
   (Gen IV move/battle particles) was confidently reported as "no files exist in any ROM" — twice, once
   even after a *raw* whole-image byte search for `{'S','P','A',' '}`. Both were wrong because **the SPA
   magic is stored byte-reversed on disk as ` APS`** (`20 41 50 53`). There are 626 in Platinum alone
   (narcs 460/461). **Lesson:** when checking for a 4CC, search **both byte orders** (NNS/SPL magics are
   frequently little-endian on disk — NSB* happen to read forward, SPL does not); and never conclude
   "format absent" from a single-orientation scan — dump the actual on-disk magic histogram first (a
   4-char census over every file, non-ASCII shown as `.`, is how ` APS` finally surfaced — see §9).

---

## 6. Decoding references (oracles, NOT dependencies)

**Gold standard — start here (memory: `nsb-gold-standard-references`):**
- **[scurest/nsbmd_docs](https://github.com/scurest/nsbmd_docs/blob/master/nsbmd_docs.txt)** — the best
  prose spec for NSBMD (node SRT, pivot/basis rotation, display list, bounding box). Confirmed our
  rotation decode exactly; note it marks the header **`BoundingBox … TODO: verify`** (so it is *not* a
  hard "bounds all bind-pose geometry" invariant — the reason ~2.5% of multi-node models decode correctly
  yet fall outside their authored box; see §10/§3).
- **[scurest/apicula](https://github.com/scurest/apicula)** — a mature *independent* Rust decoder
  (`src/nitro/*.rs`: `model.rs`, `render_cmds.rs`). Different author/language, so agreement with it breaks
  the shared-bug risk. `WebFetch` the raw source to cross-check.
- **Also independent:** Gericom/EveryFileExplorer (C#), kiwi.ds docs. Weaker/derivative: Tinke (doesn't
  open all NSBMD variants), DSPRE incl. its Avalonia branch (delegates model work to an Apicula/g3dcvtr
  backend — good for Gen4 wiring, not a fully independent decoder).

**Caveat (gold standard ≠ infallible):** Apicula bakes `T*R*S` with **no segment-scale-compensate**
(`model.rs`), i.e. our OLD compose — it matches the g3dcvtr box at only 75% multi-node vs our separate
scale's 96%. On hierarchical scale/SSC *ours* is better-validated. So **triangulate** (gold standard +
g3dcvtr's own header box + the decoder-independent vertex-count oracle); don't defer to any single source.
Same lesson applies to the jar below, which has been caught wrong (NSBTA reads negative fx16 constants as
unsigned; our signed reading is correct).

**Convenient second opinion — the reference jar:** `Nds4j/NitroSystemTool.jar` (gitignored; package
`nitroreader`, from decaf-nds/original_nds4j_repo) reads all NSB* formats and is byte-level and
`javap`-able, which makes it fast to check operand widths and struct offsets. Use it, but **as a second
opinion, not the arbiter**, and **never wrap or depend on it** — everything is RE'd natively and
round-trips byte-exact (`TECH_DEBT.md` and the memory notes state this).

No decompiler is available; use `javap`. This is how the SBC table in §2 was verified:
```
JAR=<workspace-root>/Nds4j/NitroSystemTool.jar
javap -c -p -classpath "$JAR" nitroreader.nsbmd.sbccommands.NODEDESC   # operand parse
jar tf "$JAR" | grep -iE "nsbca|nsbmd"                                  # find classes
```
Classes used for the done tasks (kept as a map for the remaining formats):
- Placement (#26): `renderer.TransfMatrix.applyParentTransf` + `gpucommands.VTX.execute` (the vertex
  math), `nitroreader.nsbmd.Node` (SRT parse, incl. `getPivotMatrix`).
- Materials/textures (#27.1): `nitroreader.nsbmd.Model.readMaterialSet`, `nsbmd.Material`.
- NSBCA (#27.2): `nsbca.{JointAnmSet,JointAnm,TagData,NodeAnimation}`, `nsbca.animtag.*` (const/variable
  trans/scale/rot + `readRot3Matrix`/`readRot5Matrix`), `renderer.ObjectGL.generateAnimation`
  (interpolation = linear).
- Remaining formats (§10): cross-check against nsbmd_docs + Apicula first, then `nitroreader.nsbta.*`
  (NSBTA), `nsbtp.*` (NSBTP), `nsbva.*` (NSBVA) as the byte-level second opinion. `g3dcvtr` (memory
  `g3dcvtr-re-resource`) is the fair-use RE target when the references are thin, especially the
  **writer/encoder** side.

---

## 7. Throwaway measurement pattern (per-node-count bucketing)

When investigating placement, don't eyeball — bucket by node count across all five ROMs. The pattern
(compile a scratch `Main` against the built classpath, run, then delete it):
```
CP=$(find Nds4j/target -name '*.jar' | head -1):Nds4j/target/classes
javac -cp "$CP" -d /tmp /tmp/Bucket.java && java -cp "/tmp:$CP" Bucket
```
`Bucket` iterates every NARC → every `BMD0` → every `Model`, compares `getDecodedBoundingBox()` vs
`getHeaderBoundingBox()` with the same tolerance as the test, and tallies `ok/total` per
`getNodeCount()`. To debug a single model, gate a `System.out` dump on a system property matching
`model.getName()` (this is how the manene SBC trace was captured — add it temporarily, **remove before
commit**). `Model` already exposes `getNodeCount()`, `isSingleNode()`, `getDecodedBoundingBox()`,
`getHeaderBoundingBox()`, `getExpectedVertexCount()`, `getVertexCount()`, `getMeshes()`, `toObj()`.

---

## 8. Tech-debt status (accurate as of `b021133`)

`TECH_DEBT.md` entries are still current:
- **§1 shared paletted-raster ("L2")** — still deferred; trigger unchanged. Nothing here changed it.
- **§2 clean-exposure principle** — followed: `Model`/`Mesh`/`Material`, `GltfExporter`,
  `SkeletalAnimationSet`/`Animation`/`NodeAnim` are all named by concept, not extension.
- **§3 3D format/library decision (glTF 2.0, pure-Java, reject LWJGL/JOGL, hand-emit glTF)** — executed
  by `GltfExporter` (base64 buffer + `ImageIO` PNG, zero native deps) and `SoftwareRenderer` (headless
  preview, pure JVM).

**Resolved debt:** node inverse-scale / SSC-flag hypothesis (turned out unused — §4); the three remaining
NSB* animation formats, MTX_SCALE, billboard oracle handling, and the preview rasterizer (all done — §10).

---

## 9. Session quirks, RE workflow, and the prioritized remaining work

### 9a. What's left — the §9 breadth list is now DONE (225 tests green)

Every item below is delivered. Rendered checkpoints in `g3d_out/` (see each entry).

1. **SPA emitter decode + particle previewer ✅** (`ParticleSet.Emitter` + `ParticleRenderer`). The full
   SPL emitter struct is RE'd (0x58 body + flag-gated scale/color/alpha/tex anim, child, six field
   modifiers), cross-checked against the independent HaroohiePals SPL reader; the emitter walk lands
   byte-exactly on the texture section over **all 3144 archives / 9290 emitters** (the self-checking
   oracle — no per-emitter size field, so any wrong width desyncs). `ParticleRenderer` simulates and
   composites a move effect into a deterministic clip (`g3d_out/spa_move_effect.gif`).
2. **OBJ import front-end + authoring ✅** (`ObjImporter`, `ModelBuilder`). `ObjImporter` parses OBJ
   (v/vt/f, polygons fan-triangulated, negative indices, corner-dedup) into the encoders' arrays.
   `ModelBuilder` authors NSBMD from them: `buildUntextured`, `buildTextured` (single material + embedded
   TEX0), and **`buildMultiTextured`** (N shapes / N materials / N textures, one TEX0). It picks a
   power-of-two `posScale` and computes the header box. All read back by `ModelSet`, vertex-count oracle
   exact, byte round-trip (`g3d_out/obj_import_torus.png`, `textured_cube.png`, `multi_material.png`).
3. **Animation writer ✅** (`AnimationBuilder`). Authors **NSBTA** (texture-SRT) from scratch — constant
   or keyframe channels (step 1/2/4; rotation as sin/cos fx16), `G3dDictionary.build` + `assembleContainer`
   — round-trips its own bytes and, via `NitroAnimation`+`SoftwareRenderer`, visibly animates a real model
   (`g3d_out/authored_nsbta.gif`). The other four formats follow the identical recipe.
4. **`MTX_SCALE` (op 0x1B) — resolved as a non-gap ✅.** Applying it *regresses* the 32 retail models'
   placement (9/32 in-box → 0/32); it is redundant with the header `posScale` and correctly consumed-not-
   applied (matches the reference no-op). Verified empirically; `Model.usesMtxScale()` + regression test.
5. **Posed billboard ✅** (`Model.poseNodeWorldTranslations`, `NitroAnimation.Frame.billboardPivotFor`): a
   BB/BBY node now face-tracks its *posed* pivot when skeletally animated, not the bind pose (verified on
   Platinum `demo_tama_a` + `g_demo_gira_a`; `g3d_out/posed_billboard.png`). **Nitro compression codec ✅**
   (`NitroLz`): general LZ10/LZ11 decompress + compress, validated on the retail ROMs (decode-to-known-magic
   400/400, round-trip identity), feeding the 3D pipeline — a real LZ11 NSBMD decompresses and renders
   (`g3d_out/lz_decompressed_model.png`).
6. **g3dcvtr byte-exact dictionary numbering — SOLVED, 46%→100% ✅.** The earlier "unrecoverable" conclusion
   was **wrong**. With `g3dcvtr.exe` now runnable under wine (`G3DCVTR/`, `wine g3dcvtr.exe foo.imd -emdl`),
   generating reference dictionaries with controlled names (author a tiny `.imd`, vary the `material_array`
   names) revealed the rule: g3dcvtr keeps **declaration order on disk** (it does *not* sort) and numbers the
   crit-bit tree's nodes in **pre-order DFS** (down-edges only, left before right). The tree *structure* is
   order-independent; only the array numbering was the gap. `G3dDictionary.reorderPreorder` applies it and
   now matches **all 5388 retail dictionaries byte-for-byte** (was 2468). So authored NSB* resource
   dictionaries are byte-identical to NITRO's own tool. (Record/sorted/data-offset insertion orders all
   plateaued at ≤46% — pre-order was the missing piece.) **RE workflow for the next agent:** `g3dcvtr foo.imd`
   converts, `g3dcvtr foo.nsbmd` dumps a binary's structure; real `.imd` samples live at
   `github.com/gainax3/retsam_00jupc/tree/HEAD/src/data/rsc`.

### 9b. Byte-identical authoring — the layered push (in progress)

The goal beyond byte-*valid* authoring is byte-*identical* output (matching g3dcvtr / retail). Status by layer:
- **Container** (NTR header + block offset table) — byte-exact (`G3dFile.assembleContainer`, verbatim blocks).
- **Resource dictionaries** — byte-exact, **100% / 5388** retail dicts (`G3dDictionary.reorderPreorder`,
  pre-order DFS, RE'd against g3dcvtr under wine — §9a.6).
- **Geometry display lists** — byte-exact, **100% / 19433** retail lists / **~47% of all MDL0 bytes**
  (`DisplayList.decodeCommands`/`encodeCommands` — the lossless GPU-command codec; `Model.Mesh.getRawDisplayList`
  exposes the verbatim stream). This is what unlocks byte-identical geometry *editing* (decode → edit a VTX
  operand → re-encode changes only those bytes) and preserves quads/strips/all vertex formats that the
  triangle view drops.
- **Whole-MDL0 re-encode** — byte-exact, **100% / 5482** retail models (`ModelSet.reencodeModels()`):
  reconstructs every model block from its decoded structure — all six dictionary kinds rebuilt via
  `G3dDictionary.build` (32676 dicts) and every display list rebuilt via the command codec (19433 DLs),
  with the fixed structs kept verbatim — and reproduces the file byte-for-byte. This is the byte-exact
  re-encode path that survives edits (the pieces that change on a geometry/resource edit are exactly the
  ones rebuilt from semantics).
- **In-place edits** (color/palette/alpha) — byte-exact (`G3dFile.writeBlockU8/U16`).
- **Authoring parity — the `.imd` &rarr; NSBMD translator (`ImdImporter`), byte-identical to g3dcvtr.** The
  key realisation: the `.imd` intermediate already carries every optimiser decision the Maya exporter made
  (`pos_s` full vs `pos_xy`/`pos_xz`/`pos_yz` deltas, strip/quad grouping, node transforms, material state),
  so *translating it faithfully reproduces g3dcvtr's bytes exactly* — no need to reimplement the exporter's
  vertex-format optimiser. `ImdImporter.toNsbmd()` parses the `.imd` and encodes every MDL0 struct (model
  header/box from `model_info`+`box_test`; the node local matrix; the SBC render stream — `NODEDESC · NODE ·
  [BB if billboard] · POSSCALE · MAT · SHP · POSSCALE|end · RET`, padded /4; the 44-byte material struct,
  layout per Apicula's `read_material` + the retail bytes; the shape struct's vertex-attribute mask
  normal(1)|color(2)|texcoord(4)) and composes them with the byte-exact primitives (`DisplayList`,
  `G3dDictionary`, `assembleContainer`). This is the native g3dcvtr replacement PDSMS needs; the genuinely
  hard optimiser is *not needed* because the `.imd` already contains its output. All fixtures + expected
  bytes are checked in (`src/test/resources/imd/`, CI-safe — no wine).
  - **All three g3dcvtr output modes are byte-exact:** `-emdl` (`toNsbmd`), `-eboth` (`toNsbmdWithTextures`,
    embedded TEX0) *and* `-etex` (`toNsbtx`, a standalone NSBTX = BTX0-wrapped TEX0). Coverage: **multi-node
    trees with full node local transforms**, **multi-material / multi-shape** textured models —
    billboard/non-billboard, hardware-lit/vertex-colored. Material state is **derived** from the `.imd`
    (`polygon_attr` = lights | mode<<4 | face-cull | alpha<<16; `teximage_param` wrap/flip from `tex_tiling`);
    `polygon_attr_mask`=`0x3f1ff8ff` and material `misc`=`0x1fce` are constant (verified across variants). The
    shape set is N structs + N DLs; the material set groups materials by shared texture/palette (dict entries
    ordered by name). Fixtures: `rock`/`book`/`pole` (single), `two`/`twotex` (multi-material/shape),
    `v_flip`/`v_decal` (material state), `star` (**3-node tree**), `xform` (**node translation + non-uniform
    scale + rotation**), `rock.nsbtx` (**-etex**), `*_both` (embedded TEX0). The `.imd` bitmap is 4-hex-digit
    big-endian words stored little-endian (`"1100"`→`00 11`); palette is `.imd` hex → LE BGR555.
  - **Node local transforms — implemented byte-exact (`encodeNodeStruct`, inverse of `Model.parseNodeLocals`).**
    The node struct is `flags(u16) · _00(fx16 = rotation[0][0]) · [translation 3×fx32] · [rotation] · [scale
    3×fx32 + inverse 3×fx32]`; flags bit0/1/2 omit an identity translation/rotation/scale, bit3 selects pivot
    compression. Rotation is stored **transposed**, `Mt = (Rz·Ry·Rx)ᵀ` from the Euler degrees (the convention,
    brute-forced against g3dcvtr and confirmed). A **principal-axis** matrix (first row-major cell that is ±1
    with a zero row+column) is **pivot-compressed** to two minor values `av,bv` with the pivot cell in flag bits
    4–7 and sign flags `0x100`(pivot −1)/`0x200`(negate c)/`0x400`(negate d); everything else writes the full
    3×3 remainder as 8×fx16. Validated byte-for-byte vs `g3dcvtr -emdl` over translation, non-uniform scale,
    ±angles about X/Y/Z (incl. the 90° multi-pivot edge), arbitrary XYZ rotations, and combined T+S+R (19 samples).
  - **Enriched class-based API (the flagship surface).** `ImdImporter.fromXml(String)`/`fromFile(File)` →
    `named(...)` → `getModelName`/`hasTextures`/`getNodeNames`/`getNodeCount`/`getMaterialNames`/`getShapeCount`
    accessors over the parsed model, then `toNsbmd()`/`toNsbmdWithTextures()`/`toNsbtx()` for bytes or
    **`toModelSet()`/`toTextureSet()`** to land directly on the flagship `ModelSet`/`TextureSet`. Those two are
    also reachable as **`ModelSet.fromImd(xml,name)`/`ModelSet.fromImd(file)`** and **`TextureSet.fromImd(...)`**,
    so `.imd` authoring is a first-class citizen of the model API. The old `static` `toNsbmd`/… shortcuts remain.
  - **Multi-*node* — implemented (`generateSbc` + generalized node set), validated against retail.** The SBC
    render stream is a general pre-order walk of the `<node_array>` with a **matrix-stack store/restore
    allocator** and a **material stack**, matching g3dcvtr byte-for-byte:
    - a node whose matrix is reused (has children, or has >1 of its own draws) is `NODEDESC`-**stored** to the
      lowest free stack slot (`0x26`); a node whose parent's matrix is no longer current **restores** it
      (`0x46`, reading the parent's saved slot). Slots free when the owner's last child is processed;
      `firstUnusedMtxStackId` = the stack high-water mark.
    - each drawing node emits `NODE · [BB/BBY] · POSSCALE · {MAT[,SHP]}* · POSSCALE|end` (POSSCALE only when
      the model is **magnified**, i.e. `pos_scale ≠ 0`); a **material used by more than one draw** is stored on
      first use (`0x24`) / restored on reuse (`0x44`).
    - **Validation:** a probe decoded every retail SBC into its node tree, regenerated it with this exact
      algorithm and compared byte-for-byte: **single-node 2806/2806 (100%), two-node 45/45 (100%), overall
      ~94.5%** across Platinum+HeartGold+Diamond. The port is covered by the `star` fixture (a null root + two
      mesh children → the canonical store/restore/POSSCALE pattern, asserted byte-exact) plus the single-node
      fixtures (regression). **Residual ~5.5%** are deep skeletal chains (a material-stack interaction on
      long same-material chains) and one matrix-slot-numbering edge in store+restore nodes. (Node **local
      transforms** — translation/scale/rotation incl. pivot-compressed rotations — are now encoded byte-exact;
      see the node-transform bullet above.)
    - The algorithm was RE'd with **Ghidra** (`analyzeHeadless` on `g3dcvtr.exe`; asserts embed
      `.\src\imd\modeltree.cpp` file/line) *plus* the retail SBC corpus as oracle — the decompilation stays
      out-of-repo (Nintendo's); only the RE'd behaviour is reproduced. Note g3dcvtr has a degenerate
      micro-optimization for a **null-root + single identity-child** (`jn` probe: it defers the child's
      `NODEDESC` past the draw) that no retail model uses; `generateSbc` emits the standard retail-style stream
      there instead (valid and renderable, but not byte-equal to that one g3dcvtr edge path).

### 9c. Animation writers — the animation half of g3dcvtr (all 5 byte-exact)

g3dcvtr's other half converts NITRO animation intermediates (`.iva`/`.itp`/`.ita`/`.ima`/`.ica`) to the NSB\*
animation binaries. No intermediate samples exist and g3dcvtr only runs intermediate→binary, so — exactly as
the model SBC was validated against retail SBCs rather than the original `.imd`s — the writers are validated by
**decode → re-encode round-trip over the whole retail corpus** (retail animations *are* g3dcvtr output). Each
`*AnimationSet` gains an `encode()` that rebuilds the file from its parsed structure (distinct from the
block-verbatim `G3dFile.save()`), and `AnimationWriterTest` re-encodes every retail file of that type (ROM-gated).

| Format | Class | Coverage | Key layout facts (RE'd this pass) |
|---|---|---|---|
| **NSBVA** visibility | `VisibilityAnimationSet.encode()`/`author()` | **15/15** | tag `V\0AV`; the +8 "unused" u16 is the animation size `12 + ceil(frames·nodes/32)·4`; frame-major node-minor bit stream |
| **NSBTP** pattern | `TexturePatternAnimationSet.encode()`/`author()` | **506/506** | tag `M\0PT`; material record ratio = `numKeyframes·4096/numFrames`; header→matdict→keyframes→tex/plt name tables; name-table order retained |
| **NSBTA** texture-SRT | `TextureSrtAnimationSet.encode()` | **548/548** | tag `M\0AT`; per material a pool of the leading const values, then from the first variable channel on **every** channel is stored (variable→array padded to 4; const→value, fx16 masked to 16 bits) |
| **NSBMA** material color | `MaterialColorAnimationSet.encode()` | **160/160** | all color arrays (u16/frame) first, region padded to 4, then all alpha arrays (u8/frame); array length = header frame count; source-offset sharing preserved, arrays in ascending-offset order |
| **NSBCA** skeletal | `SkeletalAnimationSet.encode()` | **825/825** | header · node-offset table · node blocks · rot3 pool · rot5 pool · keyframe arrays; arrays grouped by section (R→T→S), each group 4-aligned and its members element-aligned; pools kept verbatim (index-, not offset-referenced) |

**NSBCA (skeletal) — the layout that made it byte-exact.** Its payload is the hardest of the five: two
**shared rotation-matrix pools** (pivot 6-byte and 5-value 10-byte entries) that every node's rotation track
indexes by `u16` (bit 15 selects the pool), plus per-node variable-length blocks whose `info` word gates
identity/base/const/variable per T/R/S section. The writer disassembles into node blocks + the two pools +
keyframe arrays and relays them out: `align4(header+nodeTable)` · node blocks (each block's extent taken from
the offset table, so per-block padding survives; the variable-track offset fields get repointed) · rot3 pool ·
rot5 pool (both verbatim — indices don't change, only the pool base offset in the header) · keyframe arrays.
The arrays are the subtle part: they're **grouped by section in the order rotation → translation → scale**,
each group starts **4-byte-aligned**, and within a group each array is **element-aligned** (fx16 = 2, fx32 = 4;
a scale array is fx-pairs so its element is the fx size, not the 2×-pair stride) and otherwise packed
contiguously; arrays shared by multiple tracks (e.g. a node's three scale axes) keep sharing one copy. Getting
those three alignment rules right (group-boundary 4, within-group element, block-extent padding) took it from
83% → 100%.

**Other optional remainder:** a glTF (vs OBJ) front-end; 2D companion formats (NCGR/NCLR/NSCR — `NitroLz`
already decompresses them). Two g3dcvtr model-path *variant flags* are also unported (both non-default, so
retail output is unaffected and `ImdImporter` matches it): **`-s`** "store all matrices on the stack" and
**`-texsrt`** "always output the texture-matrix data field". Nothing here blocks PDSMS dropping g3dcvtr for
model conversion or for any of the five animation types.

### 9b-note. Editable model source for a Gen IV decomp — a `model.json` direction (NOT a priority; parked idea)

Explored briefly with the maintainer; recorded so a future agent can pick it up if the pokeplatinum /
pokeheartgold decomps ever want **in-tree editable models, porymap-style** (today they commit NARC binaries
as a stopgap). The key facts that shape any solution:

- **Information ordering:** `NSBMD ⇄ IMD` are information-equivalent (both carry the DS *encoding* — vertex
  command formats, `pos_scale`, struct state — proven by the byte-exact translator both directions). Every
  hardware-agnostic interchange format (**glTF, COLLADA, USD, OBJ, FBX**) sits strictly *below* them: it has
  geometry/materials but not the encoding, so `IMD→glTF` is lossy and no `glTF→IMD` can be its inverse. So
  **byte-exact matching of an existing model goes through IMD/NSBMD, never through an open format**; to clone
  a retail model exactly you *decompile* `NSBMD→IMD`, not import glTF.
- **The decomp contract only needs byte-exact for *unedited* models** (vanilla must still rebuild the ROM);
  an *edited* model just has to re-encode to a *valid* NSBMD. That split is what makes an editable pipeline
  feasible at all.
- **No existing standard hits all of {byte-exact-lossless, git-diffable, DCC-editable}** — glTF fails
  lossless, NARC fails diffable/editable, IMD is lossless+text but is Nintendo's verbose XML and *cannot be
  opened in Blender or any DCC* (it's a build intermediate, not an editing format; COLLADA is the closest-
  fitting standard — Phong/Lambert ≈ NITRO material, native quads — but its tooling has decayed). So the
  right move is a **decomp-defined text source**, à la pokeemerald's `map.json`:
  - **`model.json`** = a clean, diffable *decompilation* of the NSBMD: nodes, materials (NITRO fixed-function
    state as readable fields), texture refs, and the geometry as the **exact display-list command stream**.
    Untouched ⇒ byte-exact rebuild; hand/tool-editable for materials/nodes/flags.
  - **Textures as PNG** (+ palette), like every pret asset.
  - **glTF only as the Blender *bridge*, never storage:** the tool exports a mesh to glTF for Blender and
    re-imports edits (re-encoding to valid); vanilla geometry keeps its preserved command stream (byte-exact).
- **The engine for that "porymap for models" tool mostly already exists in this repo:** `ModelSet`/`Model`
  (+ `reencodeModels()` byte-exact re-encode), `DisplayList.decodeCommands`/`encodeCommands` (byte-exact
  geometry round-trip + edit) + `Model.Mesh.getRawDisplayList`, `ImdImporter`'s section encoders +
  `G3dDictionary`, `SoftwareRenderer`/`ModelViewer` (render/inspect UI substrate), `GltfExporter` (Blender
  bridge — a glTF *importer* is the one missing piece), `TextureSet` (texture decode/encode). A decomp would
  build the `NSBMD→model.json` decompiler + `model.json→NSBMD` builder on top of these.

Bottom line for a future taker: **binary stays the matching asset; a `model.json` (lossless decompilation) +
PNG + a glTF Blender-bridge is the editable-source path; the byte-exact primitives are already here.**

### 9b. RE workflow for a new/undocumented format (how SPA and NSBMA were cracked)
- **Magic census first.** Before assuming anything, tally the 4-char magic of *every* file in *every*
  NARC (non-ASCII → `.`), and search **both byte orders**. This is what surfaced ` APS` (§5.9). A throwaway
  `Main` on `Nds4j/target/classes` + the maven dep classpath (`mvn -f Nds4j/pom.xml
  dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt`) is the pattern (see §7).
- **Crack bitfields by correlating a field against a size/count you already trust.** The SPT `texParam`
  didn't decode as a standard `texImageParam`; dumping `param` vs `texelSize` across ~16 blocks showed
  `format=p&7`, `w=8<<((p>>4)&7)`, `h=8<<((p>>8)&7)` (each candidate width×height×bpp had to equal the
  texel size). Same trick fixed NSBMA's color-vs-alpha strides (adjacent materials' offset spacing =
  `frames×stride`).
- **Byte-exact round-trip is nearly free and is the real correctness bar** (§0): keep the raw bytes,
  return them from `save()`. Decode is a read-only *view* on top; a partial decode still ships a correct,
  round-tripping reader (that's exactly `ParticleSet`'s emitter handling).

### 9c. Encoder / rendering gotchas (bit me this session)
- **DIRECT/BGR555 encode must use `>>3`, not `*31/255`.** The decoder is `bits<<3`; only `>>3` is its
  exact inverse. `*31/255` rounds high values wrong (248→30→240) and silently costs byte-exactness.
- **NNS dictionary node *numbering* is unrecoverable, but validity isn't.** `G3dDictionary.build` emits a
  functionally-correct canonical tree (100% lookups) that is byte-exact only 46% of the time — the
  critbit *multiset* always matches retail; only array order differs (lost pre-sort authoring order).
  Byte-valid is enough to author; byte-exact re-encode of an existing file = verbatim preservation (§10 F4).
- **Animated GIF disposal must be `"none"`** in the `javax.imageio` metadata tree — `"restoreToBackground"`
  is an *invalid* value and throws `IIOInvalidTreeException` (`AnimatedGif`).
- **Swing offscreen/headless:** `ModelViewer.renderView` paints straight to a `BufferedImage` (no
  `JFrame` — that throws `HeadlessException`); the `ModelViewerFrame` shell is only built with a display.
  This is why the viewer can snapshot itself in tests/CI.
- **DS display-list encoding trick:** emit **one command per 4-byte word, NOP-padded** (`[op,0,0,0]` then
  params). NOP consumes no operands, so the decoder stays in sync — far simpler than packing 4 real
  opcodes/word, and geometry-exact (`DisplayList`).
- Rendered checkpoints for every milestone are in **`g3d_out/`** (walk/scroll/flip-book/recolor/billboard/
  particle sheets + GIFs, animated `.gltf`, authored `.nsbtx`/`.nsbmd`). `g3d_out/` is outside the repo,
  not committed.

---

## 10. Roadmap: F1–F4 status — **all DONE**; remaining work is §9's breadth list

The §4 numbered tasks and every earlier §10 follow-up are done (all read formats byte-exact; glTF export;
`SoftwareRenderer`; billboard oracle handling; MTX_SCALE resolved — §1). The full F1–F4 forward roadmap
below is **delivered**: animate, export, view (incl. billboards), the seventh+SPA read formats, in-place
editing, and authoring both NSBTX and NSBMD from scratch. What's genuinely left is **breadth, not
blockers** — see the prioritized list in **§9**.

### F1 — Animate the preview and the export ✅ DONE
- **`SoftwareRenderer` (animated).** `NitroAnimation` (new) composes the four tracks into a per-frame
  `Frame` parallel to `getMeshes()`: NSBCA pose (`Model.pose`), **NSBTA** as a normalised-UV matrix on
  texcoords, **NSBTP** as a per-mesh texture/palette override, **NSBVA** as a per-mesh draw flag. New
  `render(Model, Frame, …)` + `renderFrames(…)` (one fitted camera per clip); `AnimatedGif` writes a
  looping GIF with pure `javax.imageio`. Verified: manene walk, demo_ana_d water scroll, gingaboss 16-frame
  flip-book, kurotama (all three of NSBVA+NSBCA+NSBTA together). Commit `2999b67`.
- **`GltfExporter` (animated).** `toGltf(model, textures, animations, textureSrt)` emits a **node tree**
  (bind-pose TRS, geometry placed per node as `raw*posScale` in local space) and one glTF animation per
  NSBCA (node T/R/S channels; row-vector→column-vector quaternion via `matrixToQuat`, sign-continuous;
  constant channels omitted). Optional NSBTA seeds `KHR_texture_transform`. Commit `c45de92`.
  **Verified geometrically** (composing the emitted tree as a viewer would reconstructs the decode and
  `Model.pose()` to <1e-3). *Known limits:* glTF core can't portably animate NSBTP/NSBVA, and non-uniform
  SSC node scale shears under standard TRS composition (SoftwareRenderer stays the NNS-exact renderer).

### F2 — Interactive viewer ✅ DONE (incl. billboards)
`ModelViewer.renderView` composites the 3D viewport + an inspection HUD (counts, animation+scrub, material
list, node list with billboard flags, texture browser) with or without a display; `ModelViewerFrame` is
the Swing shell (mouse-orbit, frame scrubber, 30fps play/pause). Pure Swing/Java2D per §3. Commit `8cabc60`.
**Camera-facing billboards done** (`efeabc3`): `SoftwareRenderer` draws a `BB`/`BBY` node's raw local
geometry around its projected world pivot in screen space (unlit), so a sprite tracks the camera as the
model orbits (Platinum `hero` stays full-face at every yaw). `Model.getNodeWorldTranslation/Scale` expose
the pivot. Uses the bind-pose pivot (billboards are effect quads, rarely skeletally posed); a per-frame
posed pivot for a skinned billboard would be the only refinement.

### F3 — Remaining read formats ✅ DONE (NSBMA + SPA)
`MaterialColorAnimationSet` (NSBMA / `BMA0`, **absent from the jar**, RE'd from files): per material, five
u32 channels (diffuse/ambient/specular/emission = 15-bit color, alpha = 5-bit); bit `0x20` = constant,
else low 16 bits are an offset (from anim start) to a per-frame array (u16/frame color, u8/frame alpha).
Byte-exact over all 160 BMA0 in the five ROMs; demo_kusari's alpha decodes as a 0→31→0 glow. Commit
`93f6e00`.

**SPA (particles) ✅ DONE** (`2719663`) — `ParticleSet`. (Earlier wrongly called N/A: the magic is stored
**byte-reversed** as ` APS`, so a forward `"SPA"` / raw `{'S','P','A',' '}` scan finds zero. Match ` APS`.)
RE'd from the files: header ` APS`, version `12_1`, `u16 emitterCount`, `u16 textureCount`, texture-section
size/offset at `+0x14`/`+0x18`. Each embedded ` TPS`(=`SPT `) texture: `u32 texParam` (format=`&7`,
width=`8<<((p>>4)&7)`, height=`8<<((p>>8)&7)`), texel size, palette offset/size, total; texels at `+0x20`.
Round-trips byte-for-byte and decodes the ` TPS` sprites (A5I3 alpha masks + the other NNS formats) to
RGBA — the glows/sparks/rings/streaks emitters draw (`g3d_out/spa_particles.png`). Validated over **all five
ROMs: 3144 files byte-exact, 8906 particle textures decoded (8886 with alpha)**; bulk in Platinum narcs
460/461. **Still TODO (breadth):** decode the per-emitter behaviour parameters (preserved verbatim now) and
the emitter→texture playback (a particle previewer). The 2D companions / Nitro compression codec in
`nds4j-3d-formats-first-class-plan` remain optional breadth.

### F4 — The writer / encoder side — **DONE end-to-end (edit + author NSBTX and NSBMD from scratch)**
- **Editing round-trip ✅.** `G3dFile.writeBlockU8/U16` make same-size in-place edits (unedited → byte-exact,
  edited → byte-valid; offset table untouched). Concrete: NSBMA `ColorChannel.setRaw/setRgb` +
  `ScalarChannel.set`; NSBTX `TextureSet.setPaletteColor` (incl. embedded TEX0 → `ModelSet.save()` re-emits
  a valid repainted model). Verified minimal/reversible + a visible manene recolor. Commit `0b8d6ad`.
- **Full conversion (the g3dcvtr replacement) — DONE end-to-end for NSBTX and NSBMD.** Every keystone
  below is tested library code; they compose into authoring both formats from scratch (`AuthorNsbtx/NsbmdTest`):
  - **`G3dDictionary.build(names, records, elemSize)`** constructs the NNS Patricia tree from scratch
    (leaf `refBit` = highest set bit of the name; internal `refBit` = highest bit where the new name
    diverges from the matched leaf; standard patricia splice) and assembles the full on-disk layout
    (header `sizeDict = 16+count*(20+elemSize)`, const `0x0008`, `12+4*count`, `(count+1)` 4-byte nodes
    `{refBit, idxLeft, idxRight, idxEntry}`, `elemSize`, `ofsData = 4+count*elemSize`, records, names).
    `G3dDictionary.lookup(name)` mirrors the DS traversal. **Validated on 5388 retail dictionaries: 100%
    functionally correct, 100% re-parse-identical, 46% byte-exact** (node numbering aligns). A valid tree
    regardless of numbering — which is what authoring needs. Bit index is LSB within the byte
    (`(name[refBit>>3] >> (refBit&7)) & 1`), missing bytes read 0.
  - **`G3dFile.assembleContainer(magic, version, blocks)`** writes the NTR header + offset table + blocks;
    rebuilds real BMA0/BTA0/BTP0 byte-for-byte.
  - **Geometry encoder DONE** (`d8f15e4`): `DisplayList.encode`/`decode` — the DS geometry command-stream
    codec (raw fx16 verts + 1.11.4 texcoords, separate triangles, one NOP-padded command per word).
    Geometry-exact: `decode(encode(mesh))` reproduces the triangles over 400 retail meshes / 17360 tris.
  - **Texture encoder DONE** (`d8f15e4`): `TextureSet.encodeTextureData` (PLTT4/16/256 index-mapping +
    color-0 transparency; DIRECT BGR555 via `>>3`, the exact inverse of decode) + `getRawTextureData` /
    `overwriteRawTextureData`. Over 600 retail textures: 100% pixel-exact, 95% byte-exact.
  - **End-to-end NSBTX authoring DONE** (`954309c`): `AuthorNsbtxTest` builds a complete valid NSBTX from
    an image with nothing pre-parsed (encode texels → `G3dDictionary.build` the tex/pltt dicts → lay out
    TEX0 → `assembleContainer`), and the production `TextureSet` reads it back pixel-exact
    (`g3d_out/authored_nsbtx.png`). The source→NSB* pipeline composes.
  - **Node numbering — RESOLVED as unrecoverable, not a gap.** For the 54% non-byte-exact dicts the
    critbit *multiset is identical* to retail (same tree); only the node array *order* differs, and that
    order reflects the original pre-sort authoring order which the sorted-on-disk file does not preserve.
    So byte-exact numbering is information-theoretically impossible from the file alone; the builder emits
    the valid canonical tree (100% functional), which is what authoring needs. Re-encoding an existing
    file byte-exact is covered by verbatim preservation (the editing round-trip), not the builder.
  - **Full NSBMD authoring DONE** (`9460ddb`): `AuthorNsbmdTest` builds a complete MDL0 from scratch
    (header + node set + SBC render commands `NODEDESC`/`SHP`/`RET` + material set + shape set + the
    encoded display list, all dicts via `build`, container via `assembleContainer`); the production
    `ModelSet` reads it back to the exact authored geometry and it round-trips its own bytes. A cube
    authored this way decodes+renders (`g3d_out/authored_nsbmd.png`). **Full source→NSB* conversion now
    works end-to-end for both NSBTX and NSBMD.**
  - **What remains is breadth, not blockers:** a glTF/OBJ front-end (parse source → the vert/tri/uv arrays
    these encoders already take), richer multi-node skinned / multi-material / multi-shape models, an
    NSBCA/NSBTA/… animation *writer* (same pattern: serialize the decoded tracks + `build` the dicts +
    `assembleContainer`), and matching `g3dcvtr`'s exact layout choices where a specific ROM slot expects
    them (RE fair-use, memory `g3dcvtr-re-resource`). **PDSMS depends on the g3dcvtr binary**; this native
    encoder path is what lets it drop that.

**Working style that paid off (repeat it):** subclass `G3dFile` → free byte-exact round-trip; RE from the
**gold standard** (nsbmd_docs + Apicula, §6) and use the jar as a byte-level second opinion (NSBMA had no
jar — RE straight from the retail files, cross-checking offset spacing to fix element strides); validate
the lossy decode value-by-value in a throwaway probe; **triangulate** (no single source is infallible —
§5.8, §6 caveat); bucket/measure, keep the split oracle floors, commit checkpoints, keep the tree clean.
Rendered checkpoints live in `g3d_out/` (walk/scroll/flip-book/recolor GIFs+PNGs, animated `.gltf`).

---

## 11. 2D image write-back + the CheerpJ (Java 8) constraint — added 2026-08-29

Outside this doc's 3D (NSB*) scope, but the current Nds4j work: the 2D image formats (`src/main/java/…/images/`)
gained **write-back** so an edited *assembled* image can be decomposed back into its source graphics. All are
driven by **NitroViewer** (the in-browser Tinke replacement — see its `HANDOFF.md`), byte-exact-tested against
the retail corpus, and **Java-8-clean** (they run in CheerpJ).

### 11a. The CheerpJ Java-8 constraint — read before adding any Nds4j API a browser consumes
CheerpJ's JRE is **Java 8** (`java.version` = `1.8.0_492`). A **Java 9+ API *call*** — `List.of`, `Map.of`,
`Optional.isEmpty`, `String.repeat`, `ByteArrayOutputStream.writeBytes`, … — compiles fine under
`source/target 8` but throws a bare **`NoSuchMethodError` (null message, empty stack)** *at runtime in the
browser*: it passes JUnit on a JDK 17/20 and dies only under CheerpJ. This bit `ModelBuilder` (it used
`List.of()`); fixed by making it Java-8-clean (`List.of`→`Arrays.asList`/`Collections`, byte-exact output
preserved). **Latent:** `SkeletalAnimationSet.encode` uses `ByteArrayOutputStream.writeBytes` (Java 11) — the
next write path a browser exercises will hit it. **Rule: avoid Java 9+ API *calls* in any class the facade can
reach.** (NitroViewer's own `nitroviewer-core` is guarded with `maven.compiler.release=8`, which turns such a
call into a *compile* error; Nds4j itself is not guarded — keep this rule in mind manually.)

### 11b. Write-back APIs added (`images/`)
- **NSCR — `Screen`.** `applyImage(image, ncgr, palette, dedupFlips)` and
  `applyImageRebuildingPalette(image, ncgr, numSubPalettes, dedupFlips)` invert `getImage`: cut an assembled
  background into an **NCGR tileset + NSCR tilemap** with **H/V-flip tile dedup** and per-cell sub-palette
  selection; **match** the existing NCLR or **rebuild** it (8bpp median-cut; 4bpp greedy per-tile sub-palette
  packing). New blank `Screen(int width, int height, long screenFormat)` ctor for from-scratch authoring.
  `ImportResult{ncgr, palette, uniqueTiles, unmatchedPixels}`. Test: `ScreenBackWriteTest` (render→apply→render
  pixel-identical incl. multi-sub-palette + 8bpp rebuild, save/reload).
- **NCER/NANR — `CellBank`.** `applyImage(cellIndex, image, ncgr, palette)` and
  `applyImageRebuildingPalette(cellIndex, image, ncgr, templatePalette)` invert `renderCell`: for each OAM,
  extract its region of the edited cell image, color-match to that OAM's sub-palette, and reuse the existing
  **`Cell.OAM.OamImage.setPixels()+save()`** primitive (which already splices into the NCGR at the OAM's
  `tileOffset`). Rebuild synthesises a new NCLR per sub-palette from the OAMs that use it, **slot 0 reserved
  for transparency**, median-cut only on >15-color overflow. `ImportResult{unmatchedPixels, palette}`. Test:
  `CellBankBackWriteTest`. NANR back-write = the same, on the cell a frame references (`frame.getCellIndex()`).
  - **Renderer bug fixed here:** `OamImage.generateImageData` now sets `oamImage.paletteIdx` from `oam.palette`,
    so a 4bpp OAM draws through **its own** 16-color sub-palette instead of always sub-palette 0 (which
    mis-colored any sprite whose OAMs use `palette != 0`). This both corrects the viewer and makes the
    back-write round-trip exact.
- **NCGR / palette.** The existing headless quantisers `IndexedImage.applyImageMatched` /
  `applyImageQuantized` (PNG→NCGR, no JPanel); `IndexedImage.medianCut` widened `private`→**package-private**
  so `Screen`/`CellBank` reuse the tested median-cut.

### 11c. Correctness bar (same spirit as §0)
Byte-exact `save()` stays the invariant — the existing `CellBankTest`/`ScreenTest` round-trip tests **must stay
green** (the sub-palette renderer fix does not touch `save()`). The write-back's own oracle is **render →
`applyImage` → render is pixel-identical** on real retail bundles (found by scanning NARCs for a coherent
NCER/NCGR/NCLR or NSCR/NCGR/NCLR set, as `CrossLayerRenderingTest` does). Run:
`mvn -f Nds4j/pom.xml -Drom.dir=<workspace-root> test`.

### 11d. NMCR + NMAR (multi-cell) — added 2026-09-01; White2 supplies the examples Gen IV lacked

The README's "likely future" 2D companions were parked for lack of examples: the **Gen IV ROMs don't use
NMCR/NMAR/NFTR**. A magic-scan of **White2.nds** (Gen V, in the workspace root) settled it — NARC-aware,
decompressing every LZ member before matching:

| format | magic | in White2 | note |
|:--|:--|:--:|:--|
| NMCR (multi-cell resource) | `RCMN` | **3181** | stored **raw** (uncompressed) inside NARCs |
| NMAR (multi-cell animation) | `RAMN` | **3181** | raw; pairs 1:1 with the NMCR in the same NARC |
| NFTR (font) | `RTFN` | 10 | raw; **not yet implemented** (example now exists) |
| NTFT/NTFP/NTFI (raw texel/pal/index) | *(headerless)* | 0 identifiable | no magic; Pokémon keeps texture data in `TEX0`/NSBTX — **still blocked on an example** |

NMCR/NMAR always co-locate with the **NCER/NANR** they build on: e.g. White2 `romFile#351` NARC members
4=NCER, 5=NANR, 6=NMCR, 7=NMAR — a complete cell→multicell→animation bundle, exactly the reference data needed
to implement *and* round-trip-test them. Six NARCs carry a full raw NMCR+NMAR pair.

**NMCR (`RCMN`, one `KBCM`/"MCBK" block, `numBlocks == 1`, no LBAL).** A bank of *multi-cells*; each multicell
composes several **NCER cells** at fixed offsets. Layout (all invariants verified across all 3181 files):
```
KBCM header @0x10: "KBCM" · u32 size · u16 multicellCount · u16 0xBEEF
                   · u32 offMulticellArray(=0x14) · u32 offCellInfoArray(=0x14+count*8)
                   · u32 reserved0(=0) · u32 reserved1(=0)          (both offsets relative to 0x18)
multicell array:   count × { u16 numCells, u16 attribute, u32 cellInfoOffset }
cell-info array:   N × { u16 cellIndex→NCER, s16 x, s16 y, u16 attr }   (attr packs palette/priority/flip)
```
`cellInfoOffset` is always the running cumulative `sum(prev numCells)*8` (no sharing), and the cell-info array
runs exactly to the section/file end (no padding) — so `MultiCellBank` fully **reconstructs** the file from
structured fields (offsets recomputed, `0xBEEF`/reserved words re-emitted) rather than preserving a byte pool.
The `attribute`/`attr` words aren't decoded field-by-field (palette/priority/flip bits) but are carried
verbatim, so nothing is lost.

**NMAR (`RAMN`, `KNBA` block + `LBAL`, `numBlocks == 2`, *no* UEXT).** Byte-for-byte the **same KNBA layout as
NANR** (animation descriptors → frame descriptors → a shared result pool, `bankHeaderExtra` always the two zero
words), so `MultiCellAnimation` mirrors `CellAnimation` almost exactly; the *only* structural differences from
NANR are `numBlocks == 2` (there is no `TXEU`/UEXT section — the write path stops after LBAL) and that a frame's
pooled result names a **multicell index** (into the NMCR), not a cell index. Elements 0/1/2 (index / SRT /
translation) and playback modes 1/2 are all present, same encodings as NANR; the result pool is preserved
verbatim exactly as `CellAnimation` does.

**Render chain:** NMAR frame → multicell (NMCR) → its NCER cells → OAMs → NCGR pixels + NCLR palette.
`MultiCellBank.setCellBank(ncer)` (with `ncer.setParentImage(ncgr)`) supplies the cells;
`MultiCellAnimation.setMultiCellBank(nmcr)` supplies the multicells; `getFrameImage(frame)` composes and applies
the frame's SRT/translation transform, reusing `CellAnimation`'s transform math.

**Files:** `images/MultiCellBank.java`, `images/MultiCellAnimation.java`. Tests (ROM-gated on **White2.nds**):
`MultiCellBankTest` + `MultiCellAnimationTest` (byte-exact `save()` over all 3181 of each; edit-accessor
persistence incl. `attr`/`attribute`; `Frame` SRT/translation/index transform accessors + element-type guards)
and `MultiCellRenderingTest` (cross-layer compose + the `IllegalStateException` guards). Same Java-8-clean rule
as §11a (CheerpJ). **Full suite green: 334 tests, 0 skipped.** The parse/save/edit layer — this session's actual
deliverable — is done and covered.

### 11d-open. Battle-sprite rendering was investigated hard; here's exactly where it stands (read before retrying)

`save()`/parse is complete. **Rendering an assembled sprite is a different, mostly game-specific problem** — a
long debugging arc (with the maintainer) established the following; do not repeat it blind. Full detail +
sources live in memory note **`bw2-graphics-narc-map`**.

- **Where NMCR/NMAR actually live:** the pokegra **battle sprites**, NARC **`a/0/0/4`** (= the old "romFile#351"),
  **20 files per Pokémon**: front NCGR = files 0/2, back = 9/11, NCER/NANR/NMCR/NMAR follow, palettes = 18(normal)
  /19(shiny). Overworld sprites (`a/0/3/0`,`a/0/3/1`) use NCER/NANR only (no NMCR).
- **The old "scanned NCGR" caveat above was WRONG.** These NCGRs are **bitmap format**: the u32 at NCGR `0x24`
  (`char_type`) is non-zero ⇒ the char data is a **plain linear raster** (nitrogfx `ConvertFromTiles4BppBitmap`),
  NOT the encrypted "scanned" reorder Nds4j's `IndexedImage` runs on it. Decoding it as a raster yields the correct
  **sprite *sheet*** (parts laid out). Reference: **`ds-pokemon-hacking/White2Upgrade`** `tools/nitrogfx/gfx.c`.
- **OAM composition, verified:** each OAM is a **rectangular crop** of the sheet at tile `(T % sheetWtiles,
  T / sheetWtiles)` (not consecutive tiles); these OAMs are **affine + double-size**, so content is centered in a
  2× box (offset `+(W/2,H/2)`); and **draw order is reversed** (OAM/cell index 0 on top). With those, a **single
  part renders pixel-correct** (Bulbasaur's head is exact).
- **The full sprite is a PUPPET/skeletal system, NOT a static compose.** The NANR holds **20 animations named per
  body part** (`head, leg_FR/FL/B, foot_FR/FL/B, body1/2, tane`=bulb, + `_2` idle variants), each driving one NCER
  cell with small per-frame deltas (breathing/squash). Naïvely overlaying all cells just piles them. Assembling
  the animated sprite needs base rig positions + orchestration that almost certainly lives in **W2 game code** —
  i.e. it's **game-specific and belongs in PokEditor / a Pokémon module, NOT Nds4j** (README: game-specific formats
  go in a separate package). Rule of thumb: Nds4j should decode the sheet and render any single cell/part; the app
  layer assembles parts into a Pokémon. (Broken screenshots I made were deleted; only `nmar_animation.gif`, a real
  translation NMAR of a small shadow object, remains in the workspace root.)

**Open NMCR/NMAR items that DO belong in Nds4j (general, not Pokémon-specific), if someone wants correct cell
rendering for any DS game:**

> **Item 1 is DONE (2026-09-01).** `IndexedImage` now has a `ScanMode.LINE_BUFFER` path: a line-buffer NCGR
> that carries an SOPC section decodes as a plain raster (no LCG), so Gen V pokegra sheets decode coherently via
> `getImage()` instead of scrambling. Both decode and encode are self-inverse → byte-exact `save()` preserved.
> This also fixed a **pre-existing SOPC round-trip bug**: `save()` recomputed the SOPC tile width/height from the
> CHAR grid, but SOPC height is often 2× (sometimes 4×/8×) the CHAR height — the original values are now captured
> and re-emitted verbatim. Tests: `LineBufferNcgrTest` (byte-exact over every White2 LINE_BUFFER NCGR;
> classification; a plain-raster coherence check). Full suite **334 green**. The Gen IV `char_type=1 + SOPC`
> outlier group (~120/ROM) routes here too — byte-exact but pixel-correctness unverified. **Item 2 (CellBank OBJ
> rendering: rectangular-crop / affine double-size / draw-order) is still open.**

1. ~~Teach `IndexedImage` the **bitmap NCGR format**~~ *(done — see the note above)* — a plain linear-raster decode/encode, distinct from the
   two paths it has today (tiled, and `convertFromScanned` which **LCG-decrypts** then rasters). The bug: BW2
   pokegra bitmaps are an *un-encrypted* raster, but Nds4j runs the LCG decrypt on them → scramble. Add a third
   branch (decode ~L219, encode ~L490) that skips the decrypt.
   **The `0x24` word is a documented bitfield `flags` (ds-pokemon-hacking / Gonhex-NOCASH), NOT an enum:** bit 0
   (`0x1`) = *"use a line buffer instead of tiles"* (= the raster/bitmap layout; this is Nds4j's mis-named
   `scanned` byte and nitrogfx's `char_type` low bit), bit 8 (`0x100`) = *unknown, maybe mapping-related* (=
   Nds4j's `vram` byte; census's `char_type==256`, Gen IV only). Only one bit is ever set at a time (no `257` in
   57,705 files). **Bit 0 means "raster layout", NOT "encrypted"** — the LCG encryption on DP/HGSS Pokémon
   sprites is an *un-flagged Game-Freak overlay* on the raster format (that's precisely why no header field
   separates encrypted-raster from plain-raster). So: `flags & 0x1` ⇒ decode as a line buffer (rename "scanned"
   → "lineBuffer"); whether to *also* LCG-decrypt is orthogonal and header-invisible (Gen IV Pokémon convention =
   yes, Gen V = no). bit 8's meaning is still open across nitrogfx/docs/us — preserve it untouched. (nitrogfx the
   tool is cruder than its own docs: `if (char_type != 0)` conflates bits 0 and 8, works for Gen V only because
   bit 8 never appears there.) **Discriminator — a full census of all 57,705
   NCGRs in the 7 workspace ROMs settled it: there is NO clean header discriminator.** `char_type` (0x24) is
   NOT binary (values 0/1/256). `numBlocks==2`/SOPC separates cleanly **only for Gen V** (White2/w2test2: every
   `char_type≠0` NCGR has SOPC, 3563/3563, 0 exceptions). For **DP/Pt/HGSS it FAILS**: ~94–97% of `char_type=1`
   files lack SOPC (bulk battle sprites) but a consistent **120–126 per ROM have `char_type=1` WITH SOPC**
   (concentrated in one non-sprite narc, e.g. HeartGold romFile155) — a real counterexample to "SOPC ⇒ plain
   bitmap." No other single field (bitDepth, mappingType, dims-specified) separates them either; only
   one-directional necessary conditions (SOPC ⇒ dims-specified & mappingType==0). **So: for Gen V key on SOPC;
   for the Gen IV outlier group do an actual decode-coherence check (LCG-decrypt vs plain-raster output) before
   classifying.** Guard two census-found edge cases: `char_type==256` (scanned-byte 0/vram-byte 1, Gen IV only,
   never SOPC — a third class) and **46 "lying" `numBlocks==2` headers in HG/SS romFile136** whose SOPC is
   truncated to 0 bytes (SOPC-start == EOF). Note the RGCN round-trip test won't catch a pixel-decode regression
   — it re-encrypts on save. Keep `save()` byte-exact. (Census aside: SOPC height is usually 2× — sometimes
   4×/8× — CHAR height, i.e. a multi-frame full extent, NOT the "power-of-2 pad" the format docs guess.)
2. Fix **`CellBank.OamImage`** OBJ rendering: rectangular-crop tile addressing, affine + double-size offset,
   reverse draw order. (The current `startByte`'s `<<mappingType` overflows on these — that's the `NegativeArray`
   crash.) These make `CellBank`/`MultiCellBank` render single cells/parts correctly; the head-render proof shows
   the math. Add a cross-layer render test asserting a known part matches.

### 11e. NFTR (font) — next up; examples now exist in White2

Not yet implemented; **White2 has ~10 `RTFN` fonts** (NARC **`a/0/2/3`** = fonts; plus overlay/arm9 copies). Follow
the exact pattern the other `images/` formats use: extend `framework.GenericNtrFile`, decode the block structure,
**byte-exact `save()`**, and a `NFTRTest` gated on White2 mirroring `CellAnimationTest` (collect `"RTFN"` via
`NtrFixtures`, assert `save()` reproduces every file). NFTR layout: `FINF` (font info) + `CGLP` (glyph bitmaps) +
`CWDH` (char widths) + `CMAP` (code→glyph maps); references: **GBATEK**, ndspy `fnt`/`nftr`, nitrogfx/nitrofont.
Keep it Java-8-clean (CheerpJ). **NTFT/NTFP/NTFI** (raw headerless texel/palette/index) remain blocked — no clean
example in the Pokémon ROMs (texture data stays in `TEX0`/NSBTX); needs a different DS title or a decompiled
filesystem where the `.ntf*` extensions are explicit.

---

## 12. Audio (SDAT) support — added 2026-08-29

Outside the 3D/2D scope but the current Nds4j work: a new package
`io.github.turtleisaac.nds4j.sound` brings **NDS audio (SDAT)** to the same bar as the rest of the
library — byte-exact container round-trip, pure-JVM decode, and a headless renderer. References: **ndspy**,
**GBATEK**, lowlines. Full detail (format layouts, offsets, correctness numbers, gotchas) lives in the
memory note **`nds4j-audio-sdat-support`**; the short version:

- **`SoundArchive` (SDAT)** — container (SYMB/INFO/FAT/FILE), named embedded files, INFO records.
  **6/6 retail SDATs byte-exact** (incl. White2's 89 MB). SDAT is a top-level packed ROM file (magic
  `SDAT`), not inside a NARC.
- **`Wave`/`WaveArchive` (SWAV/SWAR)** — PCM8/PCM16/**IMA-ADPCM** → 16-bit PCM (`Adpcm` shared step
  machine). Platinum `WAVE_ARC_PV*` = Pokémon cries.
- **`Stream` (STRM)** — block-interleaved multi-channel stream. White2 `STRM_TITLE` = 112 s stereo.
- **`InstrumentBank` (SBNK)** — single/drum-set/key-split instruments, `resolve(program,note)`.
  **521/521 byte-exact.** *Trap:* the BANK INFO record is **12 bytes** — `u16 fileId`, `u16 unknown`,
  then the 4 `u16` waveArc slots (read them at offset 4, not 2).
- **`Sequence`/`SequenceArchive` (SSEQ/SSAR)** — MIDI-like bytecode. **1013/1013 SSEQ byte-exact.**
- **`SequencePlayer`** — SSEQ+SBNK+SWAR **software synth → stereo PCM** (a *render*, like
  `SoftwareRenderer`, not a round-trip). `forSequence(sdat, i)` is the one-call entry point.
  16-voice cap + `tanh` soft-limiter (the raw polyphonic sum clips hard without it).
- **`WavFile`** (pure-JVM RIFF writer, CheerpJ-safe), **`WaveformRenderer`** (headless envelope image).

Tests: `src/test/java/.../sound/{SoundArchiveTest,SequencePlayerTest}.java` (ROM-gated). Whole suite green.
**Same Java-8-clean rule as §11a** (NitroViewer/CheerpJ). Rendered/exported checkpoints (waveform PNGs +
`.wav`) written to `g3d_out/` and the workspace root. This feeds NitroViewer's planned audio browser/preview.

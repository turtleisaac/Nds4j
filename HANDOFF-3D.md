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
> **NSBVA** (visibility), and **NSBMA** (material-colour, RE'd from files — not in the jar).
> `NitroAnimation` composes the four playable tracks per frame; `AnimatedGif` writes looping previews;
> `ModelViewer` is a headless-capable Swing/Java2D viewer (orbit, scrub, play, inspect, texture browser).
> The **writer foundation** (`G3dFile.writeBlockU8/U16`) supports byte-valid in-place edits — NSBMA
> colour/alpha keyframes and NSBTX palette recolour (incl. embedded TEX0, so `ModelSet.save()` re-emits
> a valid repainted model). Camera-facing **BB/BBY billboards** render. The **encoder** is real: NNS
> Patricia dictionary builder (`G3dDictionary.build`, 5388 dicts 100% functional) + container assembler +
> **geometry encoder** (`DisplayList`, geometry-exact over 400 meshes) + **texture encoder**
> (`TextureSet.encodeTextureData`, 600 textures 100% pixel-exact) — composed into **authoring a valid
> NSBTX and a full NSBMD from scratch** (MDL0 assembly + geometry encoder), both read back by the
> production decoders. **SPA** particle archives read too (`ParticleSet`: 3144 files byte-exact, 8906
> particle sprites decoded). **225 tests green — the F1–F4 roadmap AND the entire §9 breadth list are complete.**
> The §9 follow-ups are now delivered too: **SPA emitter decode + particle previewer** (`ParticleSet.Emitter`,
> `ParticleRenderer`), an **OBJ import front-end** with **textured and multi-shape/multi-material authoring**
> (`ObjImporter`, `ModelBuilder`), an **animation writer** (`AnimationBuilder`, NSBTA), **MTX_SCALE** resolved
> as a non-gap, a **posed billboard** pivot, and a general **Nitro LZ10/LZ11 codec** (`NitroLz`). See §4 for the
> #26 fix, §6 for the gold-standard references, §9a for the delivered breadth list, and §10 for the roadmap.

Read this alongside `TECH_DEBT.md` (the *decided* design constraints) and the memory notes
`nds4j-3d-formats-first-class-plan` and **`nsb-gold-standard-references`** (start RE from
scurest/nsbmd_docs + Apicula — see §6). This doc is the *working* handoff; `TECH_DEBT.md` is the durable
policy.

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
| `ParticleSet` (SPA/SPL) | " APS" particle archive → **fully-decoded emitters** (`Emitter`: spawn/velocity/life/colour+scale+alpha curves/fields) + " TPS" sprite textures; byte-exact | done |
| `ParticleRenderer` | headless pure-JVM previewer that **plays** an SPA move effect (simulate emitters → additive sprite composite → deterministic clip) | done |
| `ObjImporter` | Wavefront OBJ (v/vt/f, polygons, negative indices) → flat vertex/uv/triangle arrays | done |
| `ModelBuilder` | author NSBMD from arrays: untextured / textured (embedded TEX0) / **multi-shape multi-material**; auto posScale + header box | done |
| `AnimationBuilder` | author **NSBTA** (texture-SRT) from scratch (constant/keyframe channels) — the animation-writer recipe | done |
| `NitroLz` (framework) | general **Nitro LZ10/LZ11** codec: decompress + compress, round-trip-exact; feeds the 3D pipeline (compressed NSBMD) | done |
| `MaterialColorAnimationSet` (NSBMA) | BMA0/MAT0 → per-material colour/alpha tracks (RE'd from files); byte-exact; **in-place editable** | done |
| `G3dFile` writer | `writeBlockU8/U16` → same-size in-place edits (NSBMA colour/alpha, NSBTX `setPaletteColor`) | done |

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
   SPL emitter struct is RE'd (0x58 body + flag-gated scale/colour/alpha/tex anim, child, six field
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
- **In-place edits** (colour/palette/alpha) — byte-exact (`G3dFile.writeBlockU8/U16`).
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
  - **Byte-exact coverage (single node):** single- and **multi-material / multi-shape** textured models —
    billboard/non-billboard, hardware-lit/vertex-coloured — model-only (`toNsbmd`) *and* with the texture
    embedded (`toNsbmdWithTextures`, the `-eboth` TEX0). Material state is **derived** from the `.imd`
    (`polygon_attr` = lights | mode<<4 | face-cull | alpha<<16; `teximage_param` wrap/flip from `tex_tiling`);
    `polygon_attr_mask`=`0x3f1ff8ff` and material `misc`=`0x1fce` are constant (verified across variants). The
    shape set is N structs + N DLs; the material set groups materials by shared texture/palette (dict entries
    ordered by name), with the SBC emitting one `MAT`/`SHP` pair per node display and the `NODEDESC` **store**
    flag + `firstUnusedMtxStackId=1` when >1 shape. Fixtures: `rock`/`book`/`pole` (single), `two`/`twotex`
    (multi-material/shape), `v_flip`/`v_decal` (material state), `*_both` (embedded TEX0). The `.imd` bitmap
    is 4-hex-digit big-endian words stored little-endian (`"1100"`→`00 11`); palette is `.imd` hex → LE BGR555.
  - **Multi-*node* — RE'd, not yet implemented (the remaining frontier).** g3dcvtr's `modeltree` rejects
    naive hand-crafted node hierarchies (`Internal Error`); a **joint root (`kind="null"`) + mesh child** is
    accepted (see `/tmp` probes `jn`=2-node, `jn3`=3-node during this work). Identity node structs are the
    same `07 f8 00 10`. The multi-node **SBC is a tree walk with matrix-stack store/restore**, e.g. `jn`:
    `NODEDESC(root, store slot0) · NODE(mesh) · POSSCALE·MAT·SHP·POSSCALE|end · NODEDESC(mesh,parent=root) ·
    RET`; siblings use `NODEDESC|0x40` (**restore** slot) — `jn3`: `… NODEDESC(mA,root) · NODEDESC+restore(mB,
    root) · RET`. **Blockers for a future taker:** (a) crafting valid multi-*mesh* `.imd` inputs is hard
    without real Maya samples — g3dcvtr silently draws only some nodes when the `node`/`display`/`matrix_array`
    binding is off; (b) the general case needs g3dcvtr's node-tree→SBC compiler (stack-slot allocation +
    store/restore ordering) and the non-identity node-transform encoding (T/R/S incl. pivot-compressed
    rotations — the inverse of `Model.parseNodeLocals`). The single-node section encoders already generalize
    list-wise; only the node set + SBC tree-walk are new.
  - **Ghidra decompilation of g3dcvtr — multi-node is a matrix-stack-allocation compiler (now readable).**
    Ran `analyzeHeadless` on `g3dcvtr.exe` (asserts embed `.\src\imd\modeltree.cpp` file/line, so functions
    are locatable). The SBC generator is one big function (in this build `FUN_0041e030`) that walks the node
    array and, per node, emits `NODEDESC`/`NODE`/`MAT`/`SHP`/`POSSCALE`/`RET` while managing a **matrix
    stack**. Behavioural facts to port from (RE'd behaviour — do NOT copy Nintendo's code):
    - emission primitives: an opcode maker (`6`=NODEDESC, `0x26`=NODEDESC|store, `0x46`=NODEDESC|restore) +
      a byte writer; a NODEDESC writes `opcode, nodeId, parentId, optByte`, then a stack-slot byte when
      store/restore is set. The store slot comes from a node field; the restore path reads a sibling's saved slot.
    - the `modeltree.cpp:281`/`406` asserts are a **stack-lookup** (`FUN_00422910`) that must find the node's
      parent on the stack — they fire (`Internal Error`) when the hand-crafted `node`/`display`/`matrix_array`
      binding doesn't put the parent where the walk expects it. That is why naive multi-mesh `.imd`s were
      rejected or drew only some nodes.
    - a node is *drawn* (gets `NODE`+`MAT`+`SHP`) only when its first field == 1 (a mesh with a display bound to
      its matrix); joint (`kind="null"`) nodes only get a `NODEDESC`. The joint-root+one-mesh `jn` case works;
      multi-*mesh* needs the stack allocation reproduced so each mesh restores the right parent matrix.
    **Port plan:** (1) reproduce the node-array walk + store/restore stack-slot allocation (push a node's matrix
    when a later sibling/child needs it, restore before the sibling draw); (2) emit NODEDESC/NODE/MAT/SHP/
    POSSCALE accordingly; (3) add the non-identity node-transform encoder (invert `Model.parseNodeLocals`);
    (4) validate against g3dcvtr on hierarchies you *can* generate (start from `jn`, grow the tree). The
    decompilation stays out-of-repo (it's Nintendo's) — the algorithm is the reference, none of the code is
    committed. A discrete, sizeable task, now de-risked by having the algorithm in hand.

**Genuinely optional remainder (not in the §9 list):** a glTF (vs OBJ) front-end; NSBCA/NSBTP/NSBVA/NSBMA
*writers* (the `AnimationBuilder` recipe generalizes); 2D companion formats (NCGR/NCLR/NSCR — `NitroLz`
already decompresses them). Nothing here blocks PDSMS dropping the g3dcvtr binary.

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
  texel size). Same trick fixed NSBMA's colour-vs-alpha strides (adjacent materials' offset spacing =
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
- Rendered checkpoints for every milestone are in **`g3d_out/`** (walk/scroll/flip-book/recolour/billboard/
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
u32 channels (diffuse/ambient/specular/emission = 15-bit colour, alpha = 5-bit); bit `0x20` = constant,
else low 16 bits are an offset (from anim start) to a per-frame array (u16/frame colour, u8/frame alpha).
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
  a valid repainted model). Verified minimal/reversible + a visible manene recolour. Commit `0b8d6ad`.
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
    colour-0 transparency; DIRECT BGR555 via `>>3`, the exact inverse of decode) + `getRawTextureData` /
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
Rendered checkpoints live in `g3d_out/` (walk/scroll/flip-book/recolour GIFs+PNGs, animated `.gltf`).

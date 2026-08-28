# Handoff: Nds4j 3D (NSB*) support

**Branch:** `feature/3d-formats` (off updated `main`). **Last commit:** `b021133`.
**Scope of this doc:** where the Nitro-3D work stands, the next tasks, and — most importantly — the
hard-won lessons and traps so the next agent doesn't repeat them.

> **Status update (tasks #26 and #27 are DONE).** Placement now composes node scale separately (the
> NNS renderer's rule, not a baked `T·R·S` matrix): multi-node placement **75%→96%**, overall **99%**.
> Materials are wired to TEX0 textures with normalised UVs; a self-contained **glTF 2.0** exporter
> (`GltfExporter`) inlines geometry + PNG textures. **NSBCA** skeletal animation is decoded byte-exact
> (round-trip 825/825 across all five ROMs; channel decode matches the reference jar exactly) and
> `Model.pose(animation, frame)` re-poses the bind-pose skeleton — the manene walk cycle renders
> posed + textured. 177 tests green. See §4 for what the numbered-task fix actually was vs. the
> hypothesis, and §10 for what's left (NSBTA/NSBTP/NSBVA, MTX_SCALE, billboards).

Read this alongside `TECH_DEBT.md` (the *decided* design constraints) and the memory note
`nds4j-3d-formats-first-class-plan`. This doc is the *working* handoff; `TECH_DEBT.md` is the durable
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
| `Model` | geometry + **node placement** + **materials→textures/UVs** + `pose(anim, frame)` | done to ~99% |
| `GltfExporter` | `Model` (+ `TextureSet`) → self-contained **glTF 2.0** (geometry + PNG textures inlined) | done |
| `SkeletalAnimationSet` (NSBCA) | BCA0/JNT0 → `List<Animation>` of per-node SRT tracks; byte-exact container | done |

**Numbers (all five ROMs, current `b021133`):**
- Container byte-exact: NSBMD **5482/5482**, NSBCA **825/825**.
- Vertex-count oracle: **5482/5482 (100%)**.
- Placement (decoded AABB vs header box): **99.0% overall**, single-node **99.4%**, multi-node **96.4%**
  (residual misses are billboard/skinning nodes a static bind-pose decode can't represent — §10).
- NSBCA channel decode vs the reference jar: exact (manene's 5 anims — 1156 T, 120 S, 9630 R samples, 0
  mismatches).

**Test suite:** 177 tests, 0 failures. Tests: `g3d/ModelSetTest.java`, `g3d/TextureSetTest.java`,
`g3d/GltfExporterTest.java`, `g3d/SkeletalAnimationSetTest.java`. Run:
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

---

## 6. The reference jar (decoding oracle, NOT a dependency)

`Nds4j/NitroSystemTool.jar` (gitignored; package `nitroreader`, from decaf-nds/original_nds4j_repo).
It reads all NSB* formats and is kept **only** to check our output against. **Never wrap or depend on
it** — everything must be reverse-engineered natively and round-trip byte-exact (`TECH_DEBT.md` and the
memory note both state this).

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
- Remaining formats (§10): `nitroreader.nsbta.*` (NSBTA texture SRT anim), `nsbtp.*` (NSBTP pattern
  anim), `nsbva.*` (NSBVA visibility anim). `g3dcvtr` (see memory `g3dcvtr-re-resource`) is the fair-use
  RE target when the jar is thin, especially for the **writer/encoder** side.

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
  by `GltfExporter` (base64 buffer + `ImageIO` PNG, zero native deps). The renders in this work used a
  throwaway pure-Java software rasterizer (kept in `/tmp`, not committed); a first-class in-library
  rasterizer/preview is still open if a headless preview is wanted.

**Resolved debt:** node inverse-scale / SSC-flag hypothesis (turned out unused — §4). **Remaining
decode gaps (all small, none break byte-exact round-trip):** see §10.

---

## 10. What's left (for the next agent)

The numbered tasks are done. Remaining, in rough priority:

1. **Other NSB* animation formats** (the memory plan `nds4j-3d-formats-first-class-plan`): **NSBTA**
   (`BTA0`, texture-SRT animation — manene ships one beside its BCA0s), **NSBTP** (`BTP0`, texture
   *pattern* animation), **NSBVA** (`BVA0`, visibility animation). Same recipe that worked here: subclass
   `G3dFile` for byte-exact round-trip first (§0), then decode the block against
   `nitroreader.{nsbta,nsbtp,nsbva}.*`, then apply on top of the model. Reference `renderer.ObjectGL`
   shows how each composes at draw time.
2. **`MTX_SCALE` (display-list op `0x1B`)** — still skipped. Small `g_demo_*` effect population; the
   vertex-count oracle is already 100%, so this only matters for those models' placement. Reference:
   `nsbmd.gpucommands.MTX_SCALE`. Do it if cheap.
3. **Billboard / skinning final pose** (`BB`/`BBY`/`NODEMIX`) — *parsed* (the SBC walk stays in sync) but
   not *posed*; a static bind-pose AABB can't represent a camera-facing billboard, so these are the
   residual ~1% placement misses. Consider excluding them from the placement oracle rather than
   "fixing" them, and render billboards only in a live viewer.
4. **First-class preview/rasterizer** — the software rasterizer used for the milestone renders lives in
   `/tmp` only. If an in-library headless preview is wanted, port it (pure Java, per §3).

**Working style that paid off (repeat it):** subclass `G3dFile` → free byte-exact round-trip; RE the
block from the reference *bytecode* (not prose); validate the lossy decode against the reference jar
value-by-value in a throwaway probe (this is how the NSBCA rotation decompression was confirmed — 0
mismatches); bucket/measure, keep the split oracle floors, commit checkpoints, keep the tree clean.

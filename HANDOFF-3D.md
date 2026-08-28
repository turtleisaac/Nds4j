# Handoff: Nds4j 3D (NSB*) support

**Branch:** `feature/3d-formats` (off updated `main`). **Last commit:** `767f072`.
**Scope of this doc:** where the Nitro-3D work stands, the next two tasks (#26, #27), and — most
importantly — the hard-won lessons and traps so the next agent doesn't repeat them.

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
| `ModelSet` (NSBMD) | MDL0 model dict → `List<Model>`, byte-exact container | done |
| `Model` | per-model geometry: display-list → meshes + **node/skeleton placement** | done to ~96% |

**Numbers (all five ROMs, current `767f072`):**
- Container byte-exact: **5482/5482**.
- Vertex-count oracle: **5482/5482 (100%)**.
- Placement (decoded AABB vs header box): **95.9% overall**, single-node **99%**, multi-node **~70%**.

**Test suite:** 169 tests, 0 failures. NSBMD tests are `g3d/ModelSetTest.java`; NSBTX is
`g3d/TextureSetTest.java`. Run: `mvn -f Nds4j/pom.xml -Drom.dir=<workspace-root> test`.

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

## 4. Next tasks

### Task #26 — segment-scale-compensate (the multi-node tail, ~30% of multi-node)
The remaining multi-node misses (`nodes=4`: 50%, `nodes=6`: 54%, `nodes=15/16/24`: low) share
**non-unit hierarchical scale**. Two things are currently discarded in `parseNodeLocals`:
- the **per-node inverse scale** — the *second* `3× fx32` at `Model.java:163` (`p += 24` reads 24 bytes
  but only the first 12, the forward scale, are used).
- the **node-flag high bits** (`flags & 0xFF00`) — never interpreted. NNS "segment scale compensate"
  (SSC) / "no-scale-in-hierarchy" hints almost certainly live here.

SSC changes how a child composes with a scaled parent (it cancels the parent's scale for that child so
scale doesn't cascade down the skeleton). The fix is in the **world-matrix composition** (`resolveWorld`
/ `multiply`), gated on the node's SSC flag, using the stored inverse scale. **Reference:** disassemble
`nitroreader.nsbmd.Node` and the SBC `execute()` methods (see §6) to see exactly when the inverse scale
is applied. Validate by the placement oracle per node-count bucket (use the throwaway bucketing probe
pattern in §7).

Also small/separate: **`MTX_SCALE` (display-list op 0x1B) is skipped** (`Model.java:299`, `pos += 12`),
so a handful of degenerate effect models (`g_demo_*`, `posScale=32`) blow up. Low population; do it only
if it's cheap.

### Task #27 — the appearance layer (make it *look* like the reference photo)
This is what actually turns correct geometry into a recognizable, posed Mime Jr. **Two independent
sub-layers, do them in this order:**
1. **Textures/materials (TEX0 → UVs on meshes).** `TextureSet` already decodes TEX0 to images. Wire the
   MDL0 **materials** (op `MAT` binds a material per shape) and the display-list **TEXCOORD** stream
   (already decoded into `Mesh.texcoords`) to the right texture, and emit UVs + material refs into the
   export. Target **glTF 2.0** for the rich version (`TECH_DEBT.md §3`), OBJ/MTL as the zero-dep
   intermediate. This alone makes models viewable-as-intended in any glTF viewer.
2. **Skeletal animation (NSBCA / `BCA0`).** A new domain class (byte-exact round-trip first, per §0),
   then apply a frame's per-node SRT on top of the bind-pose skeleton to pose the model. **manene ships
   in a NARC beside 16 `BCA0` + a `BTA0`** — confirmed HG/SS file #322, Pt #142. Reference jar:
   `nitroreader.nsbca.*` (`JointAnm`, `NodeAnimation`, `animtag/JointAnm{Rot,Scale,Trans}*`).

**Sequencing note:** #26 (bind pose) should land before #27's animation, because animation composes on
top of the bind-pose skeleton — if the bind pose is wrong, animated poses are wrong too.

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
Key classes for the next tasks:
- Task #26: `nitroreader.nsbmd.Node`, `nitroreader.nsbmd.sbccommands.*` (`execute()` shows matrix ops).
- Task #27: `nitroreader.nsbca.{JointAnm,JointAnmSet,NodeAnimation}`, `nsbca.animtag.*`,
  `nitroreader.nsbmd.Material`, `nsbmd.gpucommands.TEXCOORD`.

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

## 8. Tech-debt status (accurate as of `767f072`)

`TECH_DEBT.md` entries are still current:
- **§1 shared paletted-raster ("L2")** — still deferred; trigger unchanged (a real 2nd/3rd consumer of
  non-NCGR indexed pixels). Nothing here changed it.
- **§2 clean-exposure principle** — still the standard; NSBMD's `Model`/`Mesh` + planned glTF export
  follow it. Apply it to NSBCA (name it by concept).
- **§3 3D format/library decision (glTF 2.0, OBJ/MTL intermediate, pure-Java rasterizer, reject
  LWJGL/JOGL, hand-emit glTF)** — still the plan; task #27 executes it.

**New, undocumented-until-now debt to be aware of (this doc is the record):**
- **Node inverse-scale + node-flag high bits are parsed-but-discarded** (`Model.java:163`, and flags
  masked to `0xFF` semantics). This is the substance of task #26; not a bug in the "byte-exact" sense
  (container still round-trips), but a decode gap that caps multi-node placement at ~70%.
- **`MTX_SCALE` (display-list 0x1B) skipped** (`Model.java:299`) — small population, folded into #26.
- **Billboard/skinning final pose** (BB/BBY/NODEMIX) is *parsed* (walk stays in sync) but not
  *rendered* (a static AABB can't represent a camera-facing billboard). These legitimately can't pass
  the bind-pose oracle; consider excluding them from the oracle rather than "fixing" them.

---

## 9. First moves for the next agent

1. `git checkout feature/3d-formats`; confirm clean tree; run the suite (169 green).
2. Skim `Model.java` §2 regions and this doc's §2/§5.
3. Start **task #26**: `javap` `nitroreader.nsbmd.Node` + SBC `execute()`, find where the inverse scale
   and SSC flag gate composition, implement in `resolveWorld`/`multiply`, measure with the §7 bucketer,
   keep the split oracle green, commit + push.
4. Then **task #27**: TEX0 materials/UVs → glTF export first (visible win), then NSBCA.
5. Keep committing checkpoints; keep the working tree clean between tasks; have an adversarial senior
   dev agent review, but **verify its diagnosis against the actual ROM bytes** (§5.5).

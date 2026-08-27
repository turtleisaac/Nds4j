Tech debt & deferred design avenues
===================================

This file tracks known-but-deliberately-deferred design work: things that are *not* bugs and *not*
blocking, but are worth revisiting when the conditions that justify them are met. Each entry states
the trigger that would make it worth doing.

---

## 1. A shared paletted-raster type across NCGR and NSBTX ("L2")

**Status:** deferred. **Trigger to revisit:** a second/third consumer genuinely needs indexed pixels
out of a non-NCGR format.

`images.IndexedImage` (NCGR) is really two things fused: a *paletted raster* (pixels + palette +
render/PNG/pixel-edit) and *the NCGR file format* (`extends GenericNtrFile`, tile/scan layout, the
RAHC header, `save()`). `g3d.Nsbtx` decodes textures straight to `BufferedImage`, so the two don't
share the paletted-raster abstraction. It's tempting to extract a `PalettedRaster` base so palette
textures and NCGR share one type.

An adversarial review concluded **do not do this yet**, for concrete reasons:

- Only 3 of 7 NSBTX formats are palette-indexed; alpha (A3I5/A5I3), direct-colour, and 4x4-compressed
  can't be a paletted raster at all — their real common denominator is `BufferedImage`, which both
  formats already share.
- Making `IndexedImage` stop `extends GenericNtrFile` is a **binary-breaking change** for a published
  (Maven v1.0.0) class, with **zero** in-repo callers relying on it — all risk, no local benefit.
- Routing NSBTX through a `Color`-returning interface would add a `java.awt.Color` allocation *per
  texel* on a path that is currently pure int math.
- It would hoist `pixels`/`bitDepth`/`width`/`height` (the byte-exact `save()` depends on them) into a
  shared base whose `resize()`/PNG/`equals()` could mutate state out from under the NCGR writer — a
  fragile-base-class trap, and the `equals()`-based image tests wouldn't even catch a regression.

**If a real consumer appears,** cut it cheaply instead: a small read-only int-based value type in
`g3d` (e.g. `IndexedTexture(int[][] indices, int[] paletteArgb)`) for the palette formats only, kept
out of `images` so there's no `images`<->`g3d` coupling and no change to the published NCGR class.
Extract a genuinely *shared* type only once >=2 formats want it, and make it an interface, not a
concrete base `IndexedImage` inherits.

---

## 2. Clean exposure principle for supported formats

**Status:** design guideline (apply going forward), not a task.

The formats that are pleasant to use expose a *domain* abstraction plus standard-format import/export,
not just a 1:1 mirror of the byte layout:

- NCGR -> `IndexedImage` (pixels, palette, `getImage()`, indexed-PNG in/out)
- NCLR -> `Palette` (colours, PNG)
- NSCR -> `Screen` (tile grid with flip/sub-palette accessors, composited `getImage()`)
- NCER -> `CellBank` (assembled cell images, write-back to the NCGR)
- NANR -> `CellAnimation` (frames/durations/transforms, rendered through NCER->NCGR)
- NSBTX -> `Nsbtx` (named textures/palettes decoded to `BufferedImage`, PNG export)

New formats should follow the same shape: a byte-exact core for round-tripping **plus** a meaningful
in-memory model and export/import via a widely-supported interchange format. This is the standard the
NSBMD work targets.

---

## 3. 3D exposure & rendering: format and library decision

**Status:** decided. Applies to NSBMD and the rest of the 3D chain.

Nds4j must stay **100% OS-agnostic** (pure JVM, no per-platform native binaries). That constraint
drives these choices:

- **Interchange / clean exposure: glTF 2.0** (Khronos). It is the most mature, universally supported
  3D interchange standard across every platform, engine, and language (web/three.js, Blender, Unity,
  Unreal, Godot, ...), is pure data (JSON + binary buffers, no native code), and carries meshes,
  materials, textures, skins, and animation — matching the whole NSB* chain. This is the primary
  "expose it beyond the byte format" target for models.
- **Zero-dependency intermediate: OBJ/MTL** for the first geometry milestone — pure text, no deps,
  viewable everywhere — before layering in glTF's richer material/skin/animation support.
- **Preview rendering: a pure-Java software rasterizer** (project + z-buffered triangle fill +
  texture sampling into a `BufferedImage`). No native code, so it runs identically on every OS.
- **Explicitly rejected: LWJGL and JOGL.** Both are excellent, but they ship per-OS native binaries
  (and need a GL/Vulkan context), which violates the OS-agnostic-at-all-costs requirement for a data
  library. If real-time GPU rendering is ever wanted, it belongs in a *separate optional* module, not
  in core Nds4j.
- **glTF writing:** emit it directly (it is JSON + a binary buffer) rather than taking a third-party
  glTF dependency, keeping the core dependency-free.

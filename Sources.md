Sources
=======

This document contains (to the best of my ability) all the sources, grouped by format, (in no particular order) that
were used to aid in the development in this library. This is in hopes that in the future, other developers will not have
to spend as much time scrounging the internet for documentation, file specifications, and source code in the same way
myself and many others have had to previously.

**Note:** For the sake of providing as much information as possible, some sources which I was aware of but did not use
for whatever reason (usually just not needing it), or found at a later point, will also be listed in this document.
These sources will be denoted as <sup>unused</sup>.

---------------

# Table of Contents
* [Nintendo DS ROM](#nintendo-ds-rom)
* [NARC](#narc)
* [NCGR](#ncgr)
* [NCLR](#nclr)
* [NCER](#ncer)
* [NANR](#nanr)
* [NSCR](#nscr)
* [NSBTX](#nsbtx)
* [NSBMD](#nsbmd)
* [NSBCA](#nsbca)
* [NSBTA](#nsbta)
* [NSBTP](#nsbtp)
* [NSBVA](#nsbva)
* [NSBMA](#nsbma)
* [SPA / SPL (particles)](#spa--spl-particles)
* [Nitro LZ compression (LZ10 / LZ11)](#nitro-lz-compression-lz10--lz11)
* [G3D resource dictionary & g3dcvtr](#g3d-resource-dictionary--g3dcvtr)

---------------

## Nintendo DS ROM
* [ndspy](https://github.com/RoadrunnerWMC/ndspy/tree/master)
  * [rom.py](https://github.com/RoadrunnerWMC/ndspy/blob/master/ndspy/rom.py)
* [DS Technical Reference](https://problemkaputt.de/gbatek.htm) <sup>unused</sup>
  * [DS Cartridges](https://problemkaputt.de/gbatek.htm#dscartridgesencryptionfirmware) <sup>unused</sup>
    * [NitroROM and NitroARC File Systems](https://problemkaputt.de/gbatek.htm#dscartridgenitroromandnitroarcfilesystems) <sup>unused</sup>

## NARC
* [ndspy](https://github.com/RoadrunnerWMC/ndspy/tree/master)
  * [narc.py](https://github.com/RoadrunnerWMC/ndspy/blob/master/ndspy/narc.py)
* [DS Technical Reference](https://problemkaputt.de/gbatek.htm) <sup>unused</sup>
  * [NitroROM and NitroARC File Systems](https://problemkaputt.de/gbatek.htm#dscartridgenitroromandnitroarcfilesystems) <sup>unused</sup>

## NCGR
* [nitrogfx](https://github.com/red031000/nitrogfx/tree/master)
  * [gfx.c](https://github.com/red031000/nitrogfx/blob/master/gfx.c)
  * [gfx.h](https://github.com/red031000/nitrogfx/blob/master/gfx.h)
  * [options.h](https://github.com/red031000/nitrogfx/blob/master/options.h)
* [Tinke](https://github.com/pleonex/tinke/tree/master)
  * [NCGR.cs](https://github.com/pleonex/tinke/blob/master/Plugins/Images/Images/NCGR.cs)
  * [ImageBase.cs](https://github.com/pleonex/tinke/blob/master/Ekona/Images/ImageBase.cs)
  * [Actions.cs](https://github.com/pleonex/tinke/blob/master/Ekona/Images/Actions.cs)
* [ROMhacking.net NDS File Formats](https://www.romhacking.net/documents/%5B469%5Dnds_formats.htm)
  * [Nintendo Character Graphic Resource (NCGR/RGCN)](https://www.romhacking.net/documents/%5B469%5Dnds_formats.htm#NCGR)
* [lowlines' Documents Page](http://llref.emutalk.net/docs/) <sup>unused</sup>
  * [Nitro Character Graphics (NCGR)](http://llref.emutalk.net/docs/?file=xml/ncgr.xml#xml-doc) <sup>unused</sup>

## NCLR
* [nitrogfx](https://github.com/red031000/nitrogfx/tree/master)
  * [gfx.c](https://github.com/red031000/nitrogfx/blob/master/gfx.c)
  * [gfx.h](https://github.com/red031000/nitrogfx/blob/master/gfx.h)
  * [options.h](https://github.com/red031000/nitrogfx/blob/master/options.h)
* [Tinke](https://github.com/pleonex/tinke/tree/master)
  * [NCLR.cs](https://github.com/pleonex/tinke/blob/master/Plugins/Images/Images/NCLR.cs)
  * [ImageBase.cs](https://github.com/pleonex/tinke/blob/master/Ekona/Images/ImageBase.cs)
  * [Actions.cs](https://github.com/pleonex/tinke/blob/master/Ekona/Images/Actions.cs)
* [ROMhacking.net NDS File Formats](https://www.romhacking.net/documents/%5B469%5Dnds_formats.htm)
  * [Nintendo Color Resource (NCLR/RLCN)](https://www.romhacking.net/documents/%5B469%5Dnds_formats.htm#NCLR)
* [lowlines' Documents Page](http://llref.emutalk.net/docs/) <sup>unused</sup>
  * [Nitro Color Resource (NCLR)](http://llref.emutalk.net/docs/?file=xml/nclr.xml#xml-doc) <sup>unused</sup>

## NCER
* [nitrogfx](https://github.com/red031000/nitrogfx/tree/master)
  * [gfx.c](https://github.com/red031000/nitrogfx/blob/master/gfx.c)
  * [gfx.h](https://github.com/red031000/nitrogfx/blob/master/gfx.h)
  * [options.h](https://github.com/red031000/nitrogfx/blob/master/options.h)
* [Tinke](https://github.com/pleonex/tinke/tree/master)
  * [NCER.cs](https://github.com/pleonex/tinke/blob/master/Plugins/Images/Images/NCER.cs)
  * [ImageBase.cs](https://github.com/pleonex/tinke/blob/master/Ekona/Images/ImageBase.cs)
  * [Actions.cs](https://github.com/pleonex/tinke/blob/master/Ekona/Images/Actions.cs)
* [ROMhacking.net NDS File Formats](https://www.romhacking.net/documents/%5B469%5Dnds_formats.htm)
  * [Nintendo Cell Resource (NCER/RECN)](https://www.romhacking.net/documents/%5B469%5Dnds_formats.htm#NCER)
* [lowlines' Documents Page](http://llref.emutalk.net/docs/)
  * [Nitro Cell Resource (NCER)](http://llref.emutalk.net/docs/?file=xml/ncer.xml#xml-doc)

## NANR
* The existing NCER implementation in this library (`images.CellBank`), which shares the same
  generic NTR header and the LBAL/UEXT label/extension section layout that an NANR reuses.
* Retail Generation IV Pokémon ROMs (Diamond/Pearl/Platinum/HeartGold/SoulSilver), used to
  reverse-engineer and validate the `KNBA` animation-bank block: byte-exact round-trip was
  confirmed against every RNAN file in all five titles.
* [lowlines' Documents Page](http://llref.emutalk.net/docs/) <sup>unused</sup>
  * [Nitro Animation Resource (NANR)](http://llref.emutalk.net/docs/?file=xml/nanr.xml#xml-doc) <sup>unused</sup>

## NSCR
* Retail Generation IV Pokémon ROMs (Diamond/Pearl/Platinum/HeartGold/SoulSilver), used to
  reverse-engineer and validate the `NRCS` screen block; byte-exact round-trip was confirmed
  against every RCSN file in all five titles.
* [ROMhacking.net NDS File Formats](https://www.romhacking.net/documents/%5B469%5Dnds_formats.htm)
  * [Nintendo Screen Resource (NSCR/RCSN)](https://www.romhacking.net/documents/%5B469%5Dnds_formats.htm#NSCR) <sup>unused</sup>
* [lowlines' Documents Page](http://llref.emutalk.net/docs/) <sup>unused</sup>
  * [Nitro Screen Resource (NSCR)](http://llref.emutalk.net/docs/?file=xml/nscr.xml#xml-doc) <sup>unused</sup>

## NSBTX
The `TEX0` block layout (the texture/palette info headers and the shared `NNS_G3dResDict`
dictionary) was reverse-engineered from a third-party reference reader, `NitroSystemTool` (its
`nitroreader` package), kept out-of-tree as a decoding reference only. The seven texture pixel
formats and the `texImageParam` bit layout are from GBATEK and the retail files themselves. Byte-exact
round-trip and correct decoding were confirmed against every BTX0 file in Diamond/Pearl/Platinum/
HeartGold/SoulSilver.
* [DS Technical Reference (GBATEK)](https://problemkaputt.de/gbatek.htm)
  * [DS 3D Texture Formats](https://problemkaputt.de/gbatek.htm#ds3dtextureformats)
* [lowlines' Documents Page](http://llref.emutalk.net/docs/) <sup>unused</sup>
  * [Nitro System Binary TeXture (NSBTX)](http://llref.emutalk.net/docs/?file=xml/btx0.xml#xml-doc) <sup>unused</sup>

## NSBMD
The `MDL0` model block (model info header, node/SBC render-command stream, material set, shape set, and the
display-list geometry) was reverse-engineered natively and validated byte-exact against every BMD0 file in
Diamond/Pearl/Platinum/HeartGold/SoulSilver, with a vertex-count and a bounding-box oracle over all 5482
models. The **gold-standard prose spec** and an **independent decoder** were the primary references;
`NitroSystemTool` (`nitroreader`) was used only as a byte-level second opinion (and was caught wrong in
places — its NSBTA reads negative fx16 constants as unsigned); no third-party reader is wrapped or depended
upon. `g3dcvtr` (see below) was used to make the authoring path byte-exact.
* [scurest/nsbmd_docs](https://github.com/scurest/nsbmd_docs) &mdash; the best prose spec for NSBMD (node
  SRT, pivot/basis rotation, display list, bounding box)
  * [nsbmd_docs.txt](https://github.com/scurest/nsbmd_docs/blob/master/nsbmd_docs.txt)
* [scurest/apicula](https://github.com/scurest/apicula) &mdash; a mature, independent (Rust) Nitro decoder;
  agreement with it breaks the shared-bug risk
  * [src/nitro/model.rs](https://github.com/scurest/apicula/blob/master/src/nitro/model.rs),
    [render_cmds.rs](https://github.com/scurest/apicula/blob/master/src/nitro/render_cmds.rs)
* [DS Technical Reference (GBATEK)](https://problemkaputt.de/gbatek.htm)
  * [DS 3D Video](https://problemkaputt.de/gbatek.htm#ds3dvideo) (geometry/display-list commands)
* Retail Generation IV Pokémon ROMs, used to reverse-engineer and validate every block byte-exact.
* [Gericom/EveryFileExplorer](https://github.com/Gericom/EveryFileExplorer) <sup>unused</sup>
* [kiwi.ds NDS file format documentation](http://llref.emutalk.net/docs/) <sup>unused</sup>

## NSBCA
The `BCA0`/`JNT0` joint (skeletal) animation block &mdash; per-node scale/rotation/translation tracks
(identity / base / const / variable, keyframed every 1/2/4 frames with linear interpolation; rotations are
u16 indices into pivot-6-byte / 5-value-10-byte pools with the 3rd basis row as a cross product) &mdash; was
reverse-engineered and validated byte-exact against every BCA0 file in all five titles; channel decode was
cross-checked value-by-value against `NitroSystemTool`'s `nitroreader.nsbca.*` (0 mismatches).
* [scurest/nsbmd_docs](https://github.com/scurest/nsbmd_docs) (node SRT / basis rotation, shared with NSBMD)
* Retail Generation IV Pokémon ROMs.

## NSBTA
The `BTA0`/`SRT0` texture-SRT animation block (per-material texture-matrix scale/rotation/translation tracks;
each channel constant or a keyframe array; rotation stored as sin/cos fx16 pairs) was reverse-engineered from
the retail files and cross-checked against `NitroSystemTool`'s `nitroreader.nsbta.*`, over 112974 samples
(the only deltas are the reference's own unsigned-fx16-constant bug; the signed reading here is correct).
Byte-exact round-trip confirmed against every BTA0 file in all five titles.
* Retail Generation IV Pokémon ROMs.

## NSBTP
The `BTP0`/`PAT0` texture-pattern (flip-book) animation block (per-material frame&rarr;texture/palette
keyframes) was reverse-engineered and validated byte-exact against every BTP0 file in all five titles, using
`NitroSystemTool`'s `nitroreader.nsbtp.*` as a byte-level second opinion.
* Retail Generation IV Pokémon ROMs.

## NSBVA
The `BVA0`/`VIS0` visibility animation block (per-node on/off bit stream) was reverse-engineered and validated
byte-exact against every BVA0 file in all five titles, using `NitroSystemTool`'s `nitroreader.nsbva.*` as a
byte-level second opinion.
* Retail Generation IV Pokémon ROMs.

## NSBMA
The `BMA0`/`MAT0` material-color animation block has **no third-party reader**, so it was worked out entirely
from the retail files: per material, five u32 channels (diffuse/ambient/specular/emission = 15-bit color,
alpha = 5-bit); bit `0x20` marks a constant, otherwise the low 16 bits are an offset to a per-frame array
(u16/frame color, u8/frame alpha). Element strides were cracked by correlating adjacent materials' offset
spacing (`frames × stride`). Byte-exact round-trip confirmed against all 160 BMA0 files in the five titles.
* Retail Generation IV Pokémon ROMs.

## SPA / SPL (particles)
The `SPA ` particle archive (stored byte-reversed on disk as `" APS"`) &mdash; the archive header, the full
per-emitter behaviour struct (0x58-byte body: spawn shape/rate, velocity, lifetime, color/scale/alpha
over-life curves, plus flag-gated child-particle and six field-modifier blocks), and the embedded `" TPS"`
(`"SPT "`) sprite textures &mdash; was reverse-engineered from the retail files and cross-checked against an
**independent, actively-maintained** open-source SPL reader. The emitter walk lands byte-exactly on the
texture section over all 3144 archives / 9290 emitters in the five titles.
* [HaroohiePals/MarioKartToolbox](https://github.com/HaroohiePals/MarioKartToolbox) &mdash; independent C#
  SPL reader
  * [src/HaroohiePals.Nitro.JNLib.Spl/SPLArchive.cs](https://github.com/HaroohiePals/MarioKartToolbox/tree/HEAD/src/HaroohiePals.Nitro.JNLib.Spl),
    [Emitter/SPLEmitter.cs](https://github.com/HaroohiePals/MarioKartToolbox/tree/HEAD/src/HaroohiePals.Nitro.JNLib.Spl/Emitter)
* [HaroohiePals/NitroEffectMaker](https://github.com/HaroohiePals/NitroEffectMaker) <sup>unused</sup>
  (a full SPL/particle editor)
* Retail Generation IV Pokémon ROMs.

## Nitro LZ compression (LZ10 / LZ11)
The general forward Nitro LZ77 codec (compression type `0x10` = LZ10, `0x11` = LZ11) used for NARC members
(graphics, maps, and packed 3D assets) &mdash; header `[type][u24 decompressedSize]` followed by flag-driven
literal / back-reference blocks, with LZ11's variable-length tokens. This is distinct from the ARM9 *backward*
BLZ variant (`framework.BLZCoder`/`CodeCompression`). Decode was validated independently: sampled real LZ
files across the five titles decompress to recognisable Nitro magics (`RGCN`/`NCGR`, `RCSN`/`NSCR`, `RECN`,
`RNAN`, and compressed `BMD0`/`BCA0`/`BTA0`), and `decompress(compress(x)) == x` over structured/random/edge
inputs.
* [DS Technical Reference (GBATEK)](https://problemkaputt.de/gbatek.htm)
  * [BIOS Decompression Functions (LZ77UnCompReadNormalWrite)](https://problemkaputt.de/gbatek.htm#biosdecompressionfunctions)
* Retail Generation IV Pokémon ROMs.

## G3D resource dictionary & g3dcvtr
Every `NSB*` block indexes its resources through a shared `NNS_G3dResDict` Patricia (crit-bit) dictionary.
Building one byte-identical to Nintendo's output required the exact **node-numbering** rule, which was
reverse-engineered by running Nintendo's own converter, **`g3dcvtr`** (the NITRO-System G3D tool), under
[wine](https://www.winehq.org/): authoring tiny intermediate `.imd` files with controlled resource names
(the intermediate schema was worked out from g3dcvtr's own field-validation errors) and diffing the output
showed g3dcvtr keeps declaration order on disk (it does not sort) and numbers the tree's nodes in
**pre-order DFS**. Applying that makes `g3d.G3dDictionary.build` byte-identical to all 5388 retail
dictionaries in the five titles.
* `g3dcvtr.exe` &mdash; the NITRO-System G3D converter (`.imd`/`.ita`/... &rarr; `.nsb*`), kept out-of-tree
  and used only as a byte-level reference/oracle (not wrapped or depended upon)
* Retail Generation IV Pokémon ROMs.

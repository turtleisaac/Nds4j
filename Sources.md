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
* [NMCR](#nmcr)
* [NMAR](#nmar)
* [NFTR](#nftr)
* [NSBTX](#nsbtx)
* [NSBMD](#nsbmd)
* [NSBCA](#nsbca)
* [NSBTA](#nsbta)
* [NSBTP](#nsbtp)
* [NSBVA](#nsbva)
* [NSBMA](#nsbma)
* [SPA / SPL (particles)](#spa--spl-particles)
* [Nitro LZ compression (LZ10 / LZ11)](#nitro-lz-compression-lz10--lz11)
* [BLZ (backward LZ) & ARM9/ARM7](#blz-backward-lz--arm9arm7)
* [NDS Audio (SDAT / SWAV / SWAR / SBNK / SSEQ / SSAR / STRM)](#nds-audio-sdat--swav--swar--sbnk--sseq--ssar--strm)
* [Banner / Icon](#banner--icon)
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

**OAM composition against a `LINE_BUFFER` (bitmap-sheet) NCGR** &mdash; a Gen V "pokegra" battle-sprite
sheet's OAMs are affine with the hardware **double-size** flag set, and their tile index names a
rectangular crop of the sheet's own tile grid rather than a run of consecutive tiles (the ordinary-sprite
assumption). The double-size/disable bit semantics are standard GBA/NDS OBJ hardware behavior, not a
Nitro-specific format detail:
* [DS Technical Reference (GBATEK)](https://problemkaputt.de/gbatek.htm)
  * [LCD OBJ - OAM Attributes](http://problemkaputt.de/gbatek-lcd-obj-oam-attributes.htm) &mdash; the
    affine/double-size and (non-affine) OBJ-disable bit meanings
* [Tonc &mdash; Affine sprites](https://gbadev.net/tonc/affobj.html) <sup>unused</sup> &mdash; a more
  approachable explanation of the same double-size behavior
* Retail **White2** (Generation V) `a/0/0/4` (pokegra) &mdash; the rectangular-crop tile addressing was
  reverse-engineered from these files directly (no third-party reader implements this path); validated
  against a known part (Bulbasaur's head cell renders to an exact known-correct size/ink-count/pixel).

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

## NMCR
The Pokémon Generation IV ROMs don't use NMCR, so it has no established third-party decoder; the
`KBCM` multi-cell bank block (multicell descriptors + a flat cell-info array of `{cellIndex→NCER,
s16 x, s16 y, attr}` records) was reverse-engineered entirely from the retail Generation V files,
with every structural invariant (the cell-info offsets, the two section offsets, the `0xBEEF`
marker) verified across the whole corpus.
* Retail **White2** (Generation V), the only fixture ROM in the workspace that ships NMCR &mdash;
  3181 `RCMN` files, byte-exact round-trip confirmed against all of them.
* [DS Technical Reference (GBATEK)](https://problemkaputt.de/gbatek.htm) <sup>unused</sup>
  * [DS Files - 2D Video](https://problemkaputt.de/gbatek-ds-files-2d-video.htm) <sup>unused</sup>,
    "Nitro Unknown Files (NMAR/NMCR)" section &mdash; GBATEK's own page marks the format
    unsolved/speculative (an 8-byte-entry guess); this library's structure was derived independently
    from the retail bytes rather than from this page.

## NMAR
Byte-for-byte the same `KNBA` animation-bank layout as NANR (see above), but with `numBlocks == 2`
(no `UEXT` section) and a frame's pooled result naming a multicell index into an NMCR rather than an
NCER cell index &mdash; reverse-engineered from the retail files by diffing its block structure
against the already-solved NANR.
* The existing NANR implementation in this library (`images.CellAnimation`), whose `KNBA` layout
  NMAR reuses almost exactly.
* Retail **White2** (Generation V) &mdash; 3181 `RAMN` files, byte-exact round-trip confirmed
  against all of them, each pairing 1:1 with an NMCR in the same NARC.
* [DS Technical Reference (GBATEK)](https://problemkaputt.de/gbatek.htm) <sup>unused</sup>
  * [DS Files - 2D Video](https://problemkaputt.de/gbatek-ds-files-2d-video.htm) <sup>unused</sup>,
    "Nitro Unknown Files (NMAR/NMCR)" section

## NFTR
The `FINF`/`CGLP`/`CWDH`/`CMAP` bitmap-font blocks were cross-checked between GBATEK's prose spec
(which turned out imprecise on the `CWDH` header) and an independent, actively-maintained C++
parser, reconciling the two against the actual retail byte layout. Glyph bitmaps are decoded to
`BufferedImage`s and a `CMAP`+`CWDH`-driven text renderer (`renderString`) is included.
* [DS Technical Reference (GBATEK)](https://problemkaputt.de/gbatek.htm)
  * [Nitro Font Resource Format](https://problemkaputt.de/gbatek-ds-cartridge-nitro-font-resource-format.htm)
* [hadashisora/NintyFont](https://github.com/hadashisora/NintyFont) &mdash; independent C++ NFTR
  reader/writer (used by NitroStudio2-adjacent tooling); the authoritative field order (in
  particular `FINF`'s `defaultCharIndex`/`CharWidths` layout and `CWDH`'s `indexBegin`/`indexEnd`/
  `ptrNext` header) came from this parser
  * [formats/NFTR/finf.cpp](https://github.com/hadashisora/NintyFont/blob/master/formats/NFTR/finf.cpp),
    [cglp.cpp](https://github.com/hadashisora/NintyFont/blob/master/formats/NFTR/cglp.cpp),
    [CWDH/cwdh.cpp](https://github.com/hadashisora/NintyFont/blob/master/formats/NFTR/CWDH/cwdh.cpp),
    [CWDH/charwidths.cpp](https://github.com/hadashisora/NintyFont/blob/master/formats/NFTR/CWDH/charwidths.cpp),
    [CMAP/cmap.cpp](https://github.com/hadashisora/NintyFont/blob/master/formats/NFTR/CMAP/cmap.cpp)
* Retail **White2** (Generation V), which ships 5 `RTFN` fonts (NARC `a/0/2/3`) &mdash; the Pokémon
  Generation IV ROMs don't use NFTR. Byte-exact round-trip confirmed against all 5.

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

## BLZ (backward LZ) & ARM9/ARM7
`binaries.MainCodeFile` wraps the ROM's main-code (ARM9/ARM7) regions, whose overlay files are commonly
BLZ-compressed &mdash; a *backward*-processed LZSS variant (decompression starts at the end of the buffer
and works toward the front), distinct from the forward `LZ10`/`LZ11` codec above. `framework.BLZCoder`/
`CodeCompression` implement it.
* [DS Technical Reference (GBATEK)](https://problemkaputt.de/gbatek.htm)
  * [BIOS Decompression Functions](https://problemkaputt.de/gbatek.htm#biosdecompressionfunctions) (the
    general LZSS token format the backward variant reuses)
* Retail Generation IV Pokémon ROMs, and their ARM9/overlay files specifically.

## NDS Audio (SDAT / SWAV / SWAR / SBNK / SSEQ / SSAR / STRM)
The `sound.*` package brings the SDAT container and its embedded formats to the same byte-exact-round-trip
bar as the rest of the library: `SoundArchive` (SYMB/INFO/FAT/FILE container), `Wave`/`WaveArchive`
(PCM8/PCM16/IMA-ADPCM &rarr; 16-bit PCM), `Stream` (block-interleaved multi-channel STRM), `InstrumentBank`
(single/drum-set/key-split instrument records), and `Sequence`/`SequenceArchive` (MIDI-like SSEQ bytecode).
Beyond decode, `SequencePlayer` is a from-scratch software synthesizer (SSEQ+SBNK+SWAR &rarr; stereo PCM)
whose envelope and per-note synthesis math (LFO vibrato, pitch sweep/portamento, PSG square + noise
channels) were reverse-engineered from an independent C# sequence player to be hardware-faithful (a
from-first-principles linear envelope sounded audibly wrong); `SoundFontExporter` maps an `SBNK` to a
standard SoundFont 2 (`.sf2`) importable into any DAW sampler, and `SequenceMidi`/`MidiSequence` convert
`SSEQ` to/from standard MIDI (used to validate the sequence decode independent of the synth).
* [RoadrunnerWMC/ndspy](https://github.com/RoadrunnerWMC/ndspy/tree/master) &mdash; the SDAT/SWAR/SBNK/SSEQ
  container layouts
* [DS Technical Reference (GBATEK)](https://problemkaputt.de/gbatek.htm)
  * [DS Files - Sound (SDAT etc.)](https://problemkaputt.de/gbatek-ds-files-sound-sdat-etc.htm)
  * [DS Sound Files - SDAT (Sound Data Archive)](http://problemkaputt.de/gbatek-ds-sound-files-sdat-sound-data-archive.htm)
  * [DS Sound Files - SBNK (Sound Bank)](http://problemkaputt.de/gbatek-ds-sound-files-sbnk-sound-bank.htm)
  * [DS Sound Files - SSEQ (Sound Sequence)](https://problemkaputt.de/gbatek-ds-sound-files-sseq-sound-sequence.htm)
* [lowlines' Documents Page](http://llref.emutalk.net/docs/) &mdash; SDAT/SBNK/SSEQ struct notes
* [vgmtrans/vgmtrans](https://github.com/vgmtrans/vgmtrans) &mdash; used to re-evaluate decode fidelity;
  the real gap found was this library's own envelope math, not the container/event decode
  * [src/main/formats/NDS/NDSInstrSet.cpp](https://github.com/vgmtrans/vgmtrans/blob/master/src/main/formats/NDS/NDSInstrSet.cpp),
    [NDSInstrSet.h](https://github.com/vgmtrans/vgmtrans/blob/master/src/main/formats/NDS/NDSInstrSet.h),
    [NDSSeq.cpp](https://github.com/vgmtrans/vgmtrans/blob/master/src/main/formats/NDS/NDSSeq.cpp)
* [Gota7/GotaSequenceLib](https://github.com/Gota7/GotaSequenceLib) &mdash; an independent C# DS sequence
  player (used by NitroStudio2); the source for the exact hardware synthesis math (192&nbsp;Hz driver
  rate, LFO sine table, pitch-sweep/portamento, PSG square + noise channel generation, the logarithmic
  volume envelope) that made `SequencePlayer`/`DsEnvelope`/`DsSynth` hardware-faithful
  * [Playback/Track.cs](https://github.com/Gota7/GotaSequenceLib/blob/master/Playback/Track.cs),
    [Channel.cs](https://github.com/Gota7/GotaSequenceLib/blob/master/Playback/Channel.cs),
    [Player.cs](https://github.com/Gota7/GotaSequenceLib/blob/master/Playback/Player.cs),
    [TimeBarrier.cs](https://github.com/Gota7/GotaSequenceLib/blob/master/Playback/TimeBarrier.cs)
* Retail Generation IV Pokémon ROMs + **White2**'s SDAT (title theme, battle tower BGM).

## Banner / Icon
`IconBanner` decodes/encodes the NDS cartridge header's icon/title banner: the 32&times;32, 4bpp, 4&times;4-tiled
icon bitmap over a 16-color BGR555 palette, and the per-language UTF-16LE titles, with the version's CRC16
checksum(s) recomputed on write.
* [DS Technical Reference (GBATEK)](https://problemkaputt.de/gbatek.htm)
  * [DS Cartridge Icon/Title](http://problemkaputt.de/gbatek-ds-cartridge-icon-title.htm)
* Retail Generation IV Pokémon ROMs + **White2**, used to validate byte-exact re-serialisation of every
  retail banner.

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

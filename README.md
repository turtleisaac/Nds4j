Nds4j
=====

[![Maven](https://maven-badges.herokuapp.com/maven-central/io.github.turtleisaac/Nds4j/badge.svg)](https://central.sonatype.com/artifact/io.github.turtleisaac/Nds4j/)
[![javadoc](https://javadoc.io/badge2/io.github.turtleisaac/Nds4j/javadoc.svg?)](https://javadoc.io/doc/io.github.turtleisaac/Nds4j)
[![License: GNU GPL 3.0](https://img.shields.io/github/license/RoadrunnerWMC/ndspy.svg?logo=gnu&logoColor=white)](https://www.gnu.org/licenses/gpl-3.0)

**Nds4j** is a <u>**WIP**</u> Java library that can help you read, modify and create a few types of files used in
Nintendo DS games, with many more coming soon.

*Note:* DSi Enhanced ROMs are currently not fully supported. Opening them using Nds4j will have adverse effects on your ROM.

> Author: Turtleisaac

This project started off as a replacement for a few Java packages which are still used by Java tool developers in the Pokémon
DS hacking community (aka pretty much only me), namely [jNdstool](https://github.com/JackHack96/jNdstool) by
[JackHack96](https://github.com/JackHack96) and [Narctowl](https://github.com/turtleisaac/Narctowl) by myself. Part of the
codebase uses [ndspy](https://github.com/RoadrunnerWMC/ndspy/tree/master)
by [RoadrunnerWMC](https://github.com/RoadrunnerWMC) as a reference and can be thought of as a Java counterpart to it.

Nds4j is suitable for use in applications written in Java or any other language which runs on the JVM.
As Nds4j is written in pure Java, it is cross-platform and should run on all platforms Java 8 or higher supports.
Note that Java doesn't support the Nintendo DS itself; Nds4j is intended to be used on your computer.

Special thanks to [red031000](https://github.com/red031000) for helping me figure some particularly annoying formats out.

Formats currently implemented
-----------------------------

| Format      | Corresponding Java Class                   | Reading | Writing | Full Editing Capability |
|:------------|:--------------------------------------------|:-------:|:-------:|:-----------------------:|
| NDS ROM     | `NintendoDsRom`                             | &check; | &check; |         &check;         |
| NARC        | `Narc`                                      | &check; | &check; |         &check;         |
| NCGR        | `images.IndexedImage`                       | &check; | &check; |         &check;         |
| NCLR        | `images.Palette`                            | &check; | &check; |         &check;         |
| NCER        | `images.CellBank`                           | &check; | &check; |         &check;         |
| NANR        | `images.CellAnimation`                      | &check; | &check; |         &check;         |
| NSCR        | `images.Screen`                             | &check; | &check; |         &check;         |
| NMCR        | `images.MultiCellBank`                      | &check; | &check; |         &check;         |
| NMAR        | `images.MultiCellAnimation`                 | &check; | &check; |         &check;         |
| NFTR        | `images.NitroFont`                          | &check; | &check; |         &cross;         |
| NSBMD       | `g3d.ModelSet` / `g3d.Model`                 | &check; | &check; |         &check;         |
| NSBTX       | `g3d.TextureSet`                             | &check; | &check; |         &check;         |
| NSBCA       | `g3d.SkeletalAnimationSet`                   | &check; | &check; |         &cross;         |
| NSBTA       | `g3d.TextureSrtAnimationSet`                 | &check; | &check; |         &check;         |
| NSBTP       | `g3d.TexturePatternAnimationSet`             | &check; | &check; |         &cross;         |
| NSBVA       | `g3d.VisibilityAnimationSet`                 | &check; | &check; |         &cross;         |
| NSBMA       | `g3d.MaterialColorAnimationSet`              | &check; | &check; |         &check;         |
| SPA / SPL   | `g3d.ParticleSet`                            | &check; | &check; |         &cross;         |
| Nitro LZ    | `framework.NitroLz` (LZ10/LZ11)              | &check; | &check; |         &check;         |
| SDAT        | `sound.SoundArchive`                         | &check; | &check; |         &cross;         |
| SWAV / SWAR | `sound.Wave` / `sound.WaveArchive`           | &check; | &check; |         &cross;         |
| SBNK        | `sound.InstrumentBank`                       | &check; | &check; |         &cross;         |
| SSEQ / SSAR | `sound.Sequence` / `sound.SequenceArchive`   | &check; | &check; |         &cross;         |
| STRM        | `sound.Stream`                               | &check; | &check; |         &cross;         |
| Banner/Icon | `IconBanner`                                 | &check; | &check; |         &check;         |
| NTFT        | `images.RawTexture`                          | &check; | &check; |         &check;         |
| NTFP        | `images.RawPalette`                          | &check; | &check; |         &check;         |
| ARM9/ARM7   | `binaries.MainCodeFile`                      | &check; |         |                          |

All of the Nitro 3D (`NSB*`) and `SPA` formats round-trip **byte-for-byte** across the retail Gen IV ROMs and
were reverse-engineered natively (no third-party reader is wrapped or depended upon). Beyond reading, the 3D
stack can **author** files from scratch and **preview** them, all in pure Java (no native/OS-specific deps):

* **Convert** &mdash; `g3d.ImdImporter` translates a NITRO intermediate model (`.imd`) into an NSBMD
  **byte-for-byte identically to Nintendo's `g3dcvtr`** (the `.imd` already carries the exporter's optimiser
  decisions, so a faithful translation reproduces its exact output; verified against `g3dcvtr` on its sample
  models). `g3d.ObjImporter` (Wavefront OBJ &rarr; geometry) + `g3d.ModelBuilder` author an NSBMD
  (untextured, textured, or multi-shape/multi-material with an embedded `TEX0`); `g3d.AnimationBuilder` authors
  an NSBTA. Two of the format's hardest-to-reproduce sections encode **byte-identically** to NITRO's own tool:
  the resource dictionaries (`g3d.G3dDictionary` emits nodes in the same pre-order `g3dcvtr` does &mdash;
  verified against all 5388 retail dictionaries), and the geometry display lists (`g3d.DisplayList`'s
  `decodeCommands`/`encodeCommands` losslessly round-trip the raw GPU command stream &mdash; verified
  byte-for-byte over all 19433 retail display lists). `g3d.ModelSet.reencodeModels()` composes both with the
  container writer to reconstruct a whole `MDL0` from its decoded structure &mdash; every dictionary and
  display list rebuilt from semantics, fixed structs kept verbatim &mdash; reproducing the file
  **byte-for-byte over all 5482 retail models** (and reproducing `g3dcvtr`'s own output exactly). This is the
  byte-exact re-encode path that survives edits.
* **Export** &mdash; `g3d.GltfExporter` (self-contained glTF 2.0, static or animated) and `Model.toObj()`.
* **Preview** &mdash; `g3d.SoftwareRenderer` (headless rasteriser), `g3d.ModelViewer`/`ModelViewerFrame` (Swing
  orbit/scrub/play), `g3d.NitroAnimation` (composes all four animation tracks), `g3d.AnimatedGif`, and
  `g3d.ParticleRenderer` (plays an `SPA` move effect).
* **Edit** &mdash; `G3dFile.writeBlockU8/U16` for byte-valid in-place edits (e.g. `NSBMA` color/alpha keyframes,
  `TextureSet.setPaletteColor` recolor incl. the embedded `TEX0`). All five animation formats
  (`NSBCA`/`NSBTA`/`NSBTP`/`NSBVA`/`NSBMA`) also have byte-exact `encode()` re-encoders, each validated by
  decode&rarr;re-encode round-trip over the whole retail corpus.

The 2D `images.*` formats (`NCGR`/`NCLR`/`NCER`/`NANR`/`NSCR`/`NMCR`/`NMAR`) round-trip byte-for-byte across
the retail ROMs and support **write-back**: an edited assembled image (`Screen.applyImage`,
`CellBank.applyImage`, + palette-rebuild variants) is decomposed back into its source tileset/cells and
spliced into the NCGR, matching or rebuilding the NCLR as needed. `NMCR`/`NMAR` (Gen V multi-cell
resource/animation, composing several `NCER` cells into a larger object) and `NFTR` (Nitro bitmap fonts,
glyph/width/map decode + rendering) round out that set; fixtures for the Gen V-only formats come from
**White2**, since the Pokémon Gen IV ROMs don't use them.

The `sound.*` package brings NDS audio (`SDAT`, and its embedded `SWAV`/`SWAR`, `SBNK`, `SSEQ`/`SSAR`,
`STRM`) to the same byte-exact-container bar, plus a pure-JVM software synthesizer
(`sound.SequencePlayer`) that renders an `SSEQ`+`SBNK`+`SWAR` to PCM, and WAV import/export.

`IconBanner` reads and writes a ROM's cartridge icon bitmap and multilingual titles: `setIcon`/`setTitle`
edit either, and `toBytes()` recomputes the version's CRC16 checksum(s) (an unedited banner reproduces its
original bytes exactly).

`NTFT`/`NTFP` (`images.RawTexture`/`images.RawPalette`) are raw, headerless formats &mdash; no magic, no
header, just pixel/color bytes &mdash; with no confirmed retail example anywhere until one turned up:
*Learn with Pok&eacute;mon: Typing Adventure* (JP: *Battle &amp; Get! Pok&eacute;mon Typing DS*) ships 7979
NTFT/NTFP pairs, one per Pok&eacute;mon "note" icon. `RawTexture` is an 8bpp indexed bitmap in plain linear
(non-tiled) order, always square (32&times;32, 64&times;64, or 128&times;128 &mdash; its side length is simply
the square root of the file size); `RawPalette` is a flat BGR555 array tightly packed to however many colors
are actually used. Byte-exact round-trip confirmed over the whole 7979-pair corpus.

Likely future supported formats
--------------------------------

These are sorted in order of their likely priority, but that order can and will change.

The entire Nitro 3D (`NSB*`) and `SPA` priority group, the `LZ10`/`LZ11` compression codec, the 2D
`NCGR`/`NCLR`/`NCER`/`NANR`/`NSCR`/`NMCR`/`NMAR`/`NFTR`/`NTFT`/`NTFP` set, and NDS audio (`SDAT` and its
companions) are all supported now (see the table above), fully reverse-engineered natively and validated
byte-for-byte against the retail ROMs. What remains:

* `NTFI` &mdash; raw index data, the third member of the NTFT/NTFP raw-texture family. Unlike NTFT/NTFP
  (now supported, see above), no retail example of this specific one has turned up yet, and its very
  existence as a real, distinct on-disk format is unconfirmed (no independent source describes it, unlike
  NTFT/NTFP which multiple community references agree on).
* The remaining Nitro compression codecs &mdash; Huffman and RLE (`framework.NitroLz` covers LZ10/LZ11;
    `framework.BLZCoder` covers the ARM-code BLZ variant)
* A glTF *import* front-end for the 3D stack (export already covers glTF; OBJ import is already done via
    `g3d.ObjImporter` + `g3d.ModelBuilder`). A glTF importer would complete a Blender-edit-and-reimport
    workflow.


A few examples of Nds4j in action
---------------------------------

```java
import Narc;
import NintendoDsRom;
import BinaryWriter;
import Endianness;
import MemBuf;

public class Example
{
    /**
     * Extract a file from inside of a provided ROM file and write it to disk
     */
    public static void example1(NintendoDsRom rom)
    {
        BinaryWriter.writeFile("a012.narc", rom.getFileByName("a/0/1/2"));
    }

    /**
     * Modify the contents of a NARC in memory
     */
    public static void example2(NintendoDsRom rom)
    {
        Narc narc = new Narc(rom.getFileByName("a/0/5/6"));
        byte[] data = narc.getFile(0);
        //dataBuf.writer() can be used to write to a buffer in memory (aka dataBuf)
        // (conversely, dataBuf.reader() can be used to read from the same buffer)
        // (do keep in mind that you'll have to keep track of the end of the buffer yourself at times)
        MemBuf dataBuf = MemBuf.create();
        MemBuf.MemBufWriter writer = dataBuf.writer();
        writer.write(data);
        int end = writer.getPosition();
        writer.setPosition(0);
        writer.writeUInt32(0xFFFFFFFFL);
        writer.setPosition(end);
        narc.setFile(0, dataBuf.reader().getBuffer()); //puts the modified byte[] back into the narc
        rom.setFileByName("a/0/5/6", narc.save()); //generates a new byte[] representing the modified narc
    }

    /**
     * Unpack the entire ROM to disk (similar to how ndstool functions)
     */
    public static void example3(NintendoDsRom rom)
    {
        rom.unpack("hg_unpacked"); //creates a folder named "hg_unpacked" in the current working directory
    }

    /**
     * Let's say you've modified the unpacked folder from example3 and want to load it back into Nds4j
     */
    public static void example4()
    {
        NintendoDsRom rom = NintendoDsRom.fromUnpacked("hg_unpacked");
        rom.saveToFile("HeartGold_Modified_2.nds", false);
    }

    /**
     * Let's say you want to unpack a narc to disk (same functionality as knarc or Narctowl)
     * And of course after some edits, you can load it back in
     */
    public static void example5()
    {
        NintendoDsRom rom = NintendoDsRom.fromFile("HeartGold.nds");
        Narc narc = new Narc(rom.getFileByName("a/0/5/6"));
        narc.unpack("a056_unpacked");

        // go use another tool or do whatever, but let's say now you want to pack it back to being a NARC

        Narc packed = Narc.fromUnpacked("a056_unpacked", true, Endianness.EndiannessType.BIG);

        // from here you can put it back into a ROM or whatever
    }


    public static void main(String[] args)
    {
        NintendoDsRom rom = NintendoDsRom.fromFile("HeartGold.nds");
        example1(rom);
        example2(rom);
        example3(rom);
        // the false makes it so it does not update the device capacity byte in the ROM header
        rom.saveToFile("HeartGold_Modified.nds", false);

        example4();
        example5();
    }
}
```


Misconceptions
--------------

Still a little confused about what exactly Nds4j is or what it's capable of?
This section will try to answer some questions you may have.

- Nds4j is a *library*, not a *program.* To use Nds4j, you have to write your
    own Java code; Nds4j is essentially a tool your code can use. This may
    sound daunting -- especially if you're not very familiar with Java -- but
    if Python is what you are more familiar with, please check out
    [ndspy](https://github.com/RoadrunnerWMC/ndspy/tree/master) <sup>**_(feature parity is not guaranteed)_**</sup>.
- Nds4j runs on your PC, not on the Nintendo DS itself. You use it to create
    and modify game files, which can then be run on the console. DS games have
    to be written in a compiled language such as C or C++ to have any hope of
    being efficient; Nds4j will never be a serious option there,
    unfortunately.
- Nds4j doesn't support every type of file used in every DS game. In fact,
    for any given game, it's likely that the majority of the game's files
    *won't* be supported by Nds4j. There's a huge amount of variety in video
    game file formats, and it would be impossible to support them all. Nds4j
    focuses on file formats used in many games, especially first-party ones.
    Support for formats that are specific to a particular game would best
    belong in a separate Java package instead.

    That said, certain parts of Nds4j (such as its support for ROM files and
    raw texture data) have to do with the console's hardware rather than its
    software, and thus should be relevant to most or all games.

    Additionally, classes within Nds4j such as `Buffer`, `MemBuf`, `MemBuf.MemBufWriter`,
    `MemBuf.MemBufReader`, and `BinaryWriter` can all be used by projects which
    use Nds4j and can provide easy reading/writing of binary data to/from files.

Distribution
------------

Nds4j is published to Maven Central. Add it to a Maven project with:

```xml
<dependency>
    <groupId>io.github.turtleisaac</groupId>
    <artifactId>Nds4j</artifactId>
    <version>1.0.0</version>
</dependency>
```

or to a Gradle project with:

```groovy
implementation 'io.github.turtleisaac:Nds4j:1.0.0'
```

Every published version is listed on [the Maven Central artifact page](https://central.sonatype.com/artifact/io.github.turtleisaac/Nds4j),
and jars are also attached to the [Releases Page](https://github.com/turtleisaac/Nds4j/releases/latest) here on GitHub.

Documentation
-------------

[Nds4j's documentation is hosted on Javadoc.io](https://www.javadoc.io/doc/io.github.turtleisaac/Nds4j/latest/index.html)


Support
-------

If you think you've found a bug in Nds4j, please [file an issue on GitHub](https://github.com/turtleisaac/Nds4j/issues/new). Thanks!

Versioning
----------

Nds4j follows [semantic versioning](https://semver.org/) to the best of my
ability. If a tool claims to work with Nds4j 1.0.2, it should also work with
Nds4j 1.2.0, but not necessarily 2.0.0. (Please note that not all of those
version numbers actually exist!)

All releases prior to Nds4j 1.0.0 should be considered unstable, as the API can and will change.

Sources
-------

A comprehensive list of sources will be maintained [here](Sources.md).

Guidelines for contributing
---------------------------

If you plan on contributing to Nds4j, please ensure that your additions meet the following criteria:
* All public methods and constructors have well-written Javadoc comments
* Debug prints have been removed or at the very least commented out
* Formats which begin with the generic NTR header should always extend `framework.GenericNtrFile`. See existing classes for examples.
* The following classes in the `framework` package should be used for the following purposes and be consistent with existing code:
  * `MemBuf` - Reading and writing binary data one value at a time.
    * `MemBuf.MemBufReader` - Reading binary data one value at a time.
    * `MemBuf.MemBufWriter` - Writing binary data one value at a time.
  * `Buffer` - Reading entire binary data (bytes) from file. 
  * `BinaryWriter` - Writing out completed binary data (bytes) to file.
* A simple yet informative `toString()` method should be included in your classes where applicable.
* An in-depth and **thoroughly tested** `equals()` method should be included in your classes where applicable.
* Exceptions should be informative. That is, the message included in them should contain the nature of the exception (aka what caused it), and when applicable, the illegal value that triggered it. Use common sense, and make sure exceptions do not contain expletives.
* Any sources you used should be added to [Sources.md](Sources.md)
* Unit tests have been written and included in the path `src/test/java/` which mirrors the placement of your code in `src/main/java`.
  * Your unit tests should test everything which you think needs to be tested. The most important test in my opinion is making sure that when you convert your object back to a `byte[]` to save it, that `byte[]` should be fed back to the constructor for the class and tested for equality.
* Limit the member variables your classes include to only what is needed to represent the simplest form of the format while retaining all functionality.
  * For example, if the file format includes offsets of some data within the file and that offset is only needed for the purpose of reading the data, you should not store it in a member variable. That offset can easily be recalculated upon writing out the file and will only serve to make things more confusing if you keep it.
  * Any member variables which you want to expose to the user need to have accessor and mutator methods made available.
* Any method, member variable, or inner class which does not need to be made available to the user should either be private or protected, depending on whether other classes need to be able to access them.
* Eliminate redundancy.
  * For example, if you need to perform compression operations for DS formats, use `framework.BLZCoder`, don't write your own redundant solution. If there is something missing from the framework class, fix the existing class instead of making a new class.
  * If you have code which multiple of your classes share, don't rewrite it in each of your classes. Either put it in a protected inner class within one of your classes and import it into the other, or if the code is general enough to have other potential applications, put it in a class in the `framework` package.
* Please do your best to make your code readable to other people! 

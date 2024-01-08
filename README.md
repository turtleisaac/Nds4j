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

| Format    | Corresponding Java Class | Reading | Writing | Full Editing Capability |
|:----------|:-------------------------|:-------:|:-------:|:-----------------------:|
| NDS ROM   | `NintendoDsRom`          | &check; | &check; |         &check;         |
| NARC      | `Narc`                   | &check; | &check; |         &check;         |
| NCGR      | `images.IndexedImage`    | &check; | &check; |         &cross;         |
| NCLR      | `images.Palette`         | &check; | &check; |         &cross;         |
| NCER      | `images.CellBank`        | &check; | &check; |         &cross;         |
| ARM9/ARM7 | `binaries.MainCodeFile`  | &check; |         |                         |

Likely future supported formats
--------------------------------

These are sorted in order of their likely priority, but that order can and will change.

* NANR
* NSCR
* NSBTX
* NSBMD
* NSBCA
* SPA

The following are themselves likely, but are not part of my immediate needs or goals due
to very fleshed out solutions such as ndspy existing:

* SDAT
* SSEQ
* SBNK
* SWAR
* SSAR


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
        byte[] data = narc.files.get(0);
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
        narc.files.set(0, dataBuf.reader().getBuffer()); //puts the modified byte[] back into the narc
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

        Narc packed = Narc.fromUnpacked("a056_unpacked", true, Endianness.BIG);

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

The current version of Nds4j can be obtained from [Apache Maven](https://central.sonatype.com/artifact/io.github.turtleisaac/Nds4j),
or from the [Releases Page](https://github.com/turtleisaac/Nds4j/releases/latest) here on GitHub.

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

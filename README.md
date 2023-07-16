Nds4j
=====

[//]: # ([![Discord]&#40;https://img.shields.io/discord/534221996230180884.svg?logo=discord&logoColor=white&colorB=7289da&#41;]&#40;https://discord.gg/RQhxAxw&#41;)

[//]: # ([![Documentation]&#40;https://img.shields.io/badge/documentation-Read%20the%20Docs-brightgreen.svg?logo=read%20the%20docs&logoColor=white&#41;]&#40;http://ndspy.readthedocs.io/&#41;)

[//]: # ([![PyPI]&#40;https://img.shields.io/pypi/v/ndspy.svg?logo=python&logoColor=white&#41;]&#40;https://pypi.org/project/ndspy/&#41;)
[![License: GNU GPL 3.0](https://img.shields.io/github/license/RoadrunnerWMC/ndspy.svg?logo=gnu&logoColor=white)](https://www.gnu.org/licenses/gpl-3.0)

**Nds4j** is a Java package that can help you read, modify and create a few types of files used in
Nintendo DS games, with many more coming soon.

> Author: Turtleisaac

This project started off as a replacement for a few Java packages which are still used by Java tool developers in the Pokémon
DS hacking community (aka pretty much only me), namely [jNdstool](https://github.com/JackHack96/jNdstool) by
[JackHack96](https://github.com/JackHack96) and [Narctowl](https://github.com/turtleisaac/Narctowl) by myself. The
codebase uses [ndspy](https://github.com/RoadrunnerWMC/ndspy/tree/master)
by [RoadrunnerWMC](https://github.com/RoadrunnerWMC) as a reference and can be thought of as a Java counterpart to it.

As I continue to work on and maintain this package, it will become the new core of [PokEditor v2](https://github.com/turtleisaac/PokEditor-v2), 
my Java tool for hacking the gen 4 Pokémon games, but feel free to use it yourself!

Nds4j is suitable for use in applications written in Java or any other language which runs on the JVM.

As Nds4j is written in pure Java, it is cross-platform and should run on all platforms Java 8 or higher supports.
Note that Java doesn't support the Nintendo DS itself; Nds4j is intended to be used on your computer.

[//]: # (Interested? Read on to see some examples, or check the [API)

[//]: # (Reference]&#40;https://ndspy.readthedocs.io/en/latest/api/index.html&#41; to see the)

[//]: # (documentation for a specific module. When you're ready to install, head over to)

[//]: # (the [Installation]&#40;#installation&#41; section!)

**Note:** Code from the [Universal Pokemon Randomizer ZX](https://github.com/Ajarmar/universal-pokemon-randomizer-zx)
is adapted for LZ decompression purposes under the rights provided by the GNU General Public License v3.0.
If there are any complaints related to this, please create a new Issue in the Issues tab here on GitHub.

A few examples of Nds4j in action
---------------------------------

```java
import com.turtleisaac.nds4j.Narc;
import com.turtleisaac.nds4j.NintendoDsRom;
import com.turtleisaac.nds4j.framework.BinaryWriter;
import com.turtleisaac.nds4j.framework.Buffer;
import com.turtleisaac.nds4j.framework.Endianness;
import com.turtleisaac.nds4j.framework.MemBuf;

import java.util.ArrayList;

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

[//]: # (- Nds4j is a *library*, not a *program.* To use Nds4j, you have to write your)

[//]: # (    own Java code; Nds4j is essentially a tool your code can use. This may)

[//]: # (    sound daunting -- especially if you're not very familiar with Java -- but)

[//]: # (    the [tutorials]&#40;https://ndspy.readthedocs.io/en/latest/tutorials/index.html&#41;)

[//]: # (    walk you through this process step-by-step for some common tasks. )

[//]: # (    If Python)

[//]: # (    is what you are more familiar with, please check out)

[//]: # (    [ndspy]&#40;https://github.com/RoadrunnerWMC/ndspy/tree/master&#41;.)
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

Documentation
-------------

[//]: # ([ndspy's documentation is hosted on Read the)

[//]: # (Docs]&#40;https://ndspy.readthedocs.io/en/latest/index.html&#41;, and the documentation)

[//]: # (source code can be found in the ``docs/`` folder in this repository. In)

[//]: # (addition to the [API)

[//]: # (reference]&#40;https://ndspy.readthedocs.io/en/latest/api/index.html&#41;, there are)

[//]: # (also)

[//]: # ([examples]&#40;https://ndspy.readthedocs.io/en/latest/index.html#a-few-examples-of-ndspy-in-action&#41;)

[//]: # (and [tutorials]&#40;https://ndspy.readthedocs.io/en/latest/tutorials/index.html&#41; to)

[//]: # (help you out!)


Support
-------

[//]: # (I spent a long time writing the documentation for ndspy, so first please)

[//]: # (double-check that your question isn't already answered in the [API)

[//]: # (reference]&#40;https://ndspy.readthedocs.io/en/latest/api/index.html&#41; or)

[//]: # ([Tutorials]&#40;https://ndspy.readthedocs.io/en/latest/tutorials/index.html&#41;)

[//]: # (sections in the documentation.)

[//]: # ()
[//]: # (If that doesn't help, you can ask me &#40;RoadrunnerWMC&#41; your questions via [the)

[//]: # (ndspy Discord server]&#40;https://discord.gg/RQhxAxw&#41;. I'll try to get back to)

[//]: # (you as quickly as I can!)

[//]: # (If you think you've found a bug in Nds4j, please [file an issue on)

[//]: # (GitHub]&#40;https://github.com/turtleisaac/Nds4j/issues/new&#41;. Thanks!)


Versioning
----------

Nds4j follows [semantic versioning](https://semver.org/) to the best of my
ability. If a tool claims to work with Nds4j 1.0.2, it should also work with
Nds4j 1.2.0, but not necessarily 2.0.0. (Please note that not all of those
version numbers actually exist!)

[//]: # (Undocumented modules are considered exempt from semantic versioning, and are)

[//]: # (subject to drastic changes at any time. This is also mentioned in the)

[//]: # ([Undocumented)

[//]: # (APIs]&#40;https://ndspy.readthedocs.io/en/latest/api/index.html#undocumented-apis&#41;)

[//]: # (section of the documentation.)
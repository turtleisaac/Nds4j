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

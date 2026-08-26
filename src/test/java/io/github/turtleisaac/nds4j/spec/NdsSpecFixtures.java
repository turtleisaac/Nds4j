package io.github.turtleisaac.nds4j.spec;

import java.util.HexFormat;

/**
 * Fixtures taken from the Nintendo DS cartridge header specification (GBATEK), independent of
 * anything this library computes or stores.
 */
final class NdsSpecFixtures
{
    private NdsSpecFixtures() {}

    /**
     * The 156-byte compressed Nintendo logo bitmap that occupies header offsets 0C0h-15Bh on
     * every retail cartridge. Its CRC-16 is the documented constant 0CF56h.
     */
    private static final String NINTENDO_LOGO_HEX =
              "24FFAE51699AA2213D84820A84E409AD"
            + "11248B98C0817F21A352BE199309CE20"
            + "10464A4AF82731EC58C7E83382E3CEBF"
            + "85F4DF94CE4B09C194568AC01372A7FC"
            + "9F844D73A3CA9A615897A327FC039876"
            + "231DC7610304AE56BF38840040A70EFD"
            + "FF52FE036F9530F197FBC08560D68025"
            + "A963BE03014E38E2F9A234FFBB3E0344"
            + "780090CB88113A9465C07C6387F03CAF"
            + "D625E48B380AAC7221D4F807";

    static byte[] nintendoLogo()
    {
        return HexFormat.of().parseHex(NINTENDO_LOGO_HEX);
    }
}

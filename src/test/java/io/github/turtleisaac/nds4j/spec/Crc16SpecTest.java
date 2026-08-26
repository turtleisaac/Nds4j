package io.github.turtleisaac.nds4j.spec;

import io.github.turtleisaac.nds4j.framework.CRC16;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Conformance tests for {@link CRC16} against the <em>specification</em>, not against this
 * library's own output.
 * <p>
 * The DS cartridge header stores two CRC-16 values (GBATEK: 015Ch "Nintendo Logo CRC16",
 * 015Eh "Header CRC16"). The algorithm is the one ndstool implements, catalogued as
 * <strong>CRC-16/MODBUS</strong>: polynomial 0x8005 reflected to 0xA001, initial value
 * 0xFFFF, reflected in and out, no final XOR.
 * <p>
 * Every expectation below comes from one of:
 * <ul>
 *   <li>the published check value for CRC-16/MODBUS,</li>
 *   <li>the GBATEK-documented constant for the Nintendo logo,</li>
 *   <li>a reference implementation written here directly from the polynomial definition,
 *       deliberately structured differently from the implementation under test.</li>
 * </ul>
 * None of them were read off the current behaviour of {@link CRC16}.
 */
@DisplayName("CRC16 conforms to the NDS header CRC specification")
class Crc16SpecTest
{
    /** The check value every CRC catalogue lists for CRC-16/MODBUS over the ASCII string "123456789". */
    private static final int MODBUS_CHECK_VALUE = 0x4B37;

    /** GBATEK: the Nintendo logo CRC16 stored at header offset 015Ch is always 0CF56h. */
    private static final int NINTENDO_LOGO_CRC = 0xCF56;

    /**
     * Reference CRC-16/MODBUS written straight from the polynomial definition. Bit-at-a-time,
     * no lookup table, no shared code with the implementation under test.
     */
    private static int reference(byte[] data)
    {
        int crc = 0xFFFF;
        for (byte b : data)
        {
            crc ^= (b & 0xFF);
            for (int bit = 0; bit < 8; bit++)
                crc = ((crc & 1) != 0) ? (crc >>> 1) ^ 0xA001 : (crc >>> 1);
        }
        return crc & 0xFFFF;
    }

    private static int actual(byte[] data)
    {
        return CRC16.calculateCrc(data) & 0xFFFF;
    }

    @Test
    @DisplayName("matches the published CRC-16/MODBUS check value for \"123456789\"")
    void publishedCheckValue()
    {
        byte[] input = "123456789".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertThat(actual(input))
                .as("CRC-16/MODBUS check value; identifies the algorithm unambiguously")
                .isEqualTo(MODBUS_CHECK_VALUE);
    }

    @Test
    @DisplayName("produces GBATEK's documented 0xCF56 for the Nintendo logo")
    void nintendoLogoConstant()
    {
        assertThat(actual(NdsSpecFixtures.nintendoLogo()))
                .as("every retail cartridge stores 0CF56h at header offset 015Ch")
                .isEqualTo(NINTENDO_LOGO_CRC);
    }

    @Test
    @DisplayName("the empty message yields the initial value")
    void emptyMessageIsInitialValue()
    {
        // By definition a CRC over zero bytes performs no polynomial division,
        // so the register still holds init. init is 0xFFFF, NOT 0x0000 -- this is
        // precisely what distinguishes the NDS variant from CRC-16/XMODEM.
        assertThat(actual(new byte[0])).isEqualTo(0xFFFF);
    }

    @Test
    @DisplayName("agrees with an independent bit-at-a-time reference over random messages")
    void differentialAgainstReference()
    {
        Random rng = new Random(0xC0FFEE);          // fixed seed: reproducible
        for (int trial = 0; trial < 2000; trial++)
        {
            byte[] data = new byte[rng.nextInt(64)];
            rng.nextBytes(data);
            assertThat(actual(data))
                    .as("CRC of %s", java.util.HexFormat.of().formatHex(data))
                    .isEqualTo(reference(data));
        }
    }

    @Test
    @DisplayName("is order-sensitive, so transposed bytes are detected")
    void detectsTransposition()
    {
        // A CRC that ignored order (e.g. a plain checksum) would pass the vectors above
        // by luck on symmetric inputs. This pins down that ordering actually matters.
        assertThat(actual(new byte[]{0x01, 0x02}))
                .isNotEqualTo(actual(new byte[]{0x02, 0x01}));
    }

    @Test
    @DisplayName("detects a single flipped bit anywhere in a header-sized message")
    void detectsSingleBitFlips()
    {
        // The reason a CRC is used at all. Over 0x15E bytes (the header CRC's span)
        // every one-bit error must change the checksum.
        byte[] base = new byte[0x15E];
        new Random(7).nextBytes(base);
        int baseline = actual(base);

        for (int index : new int[]{0, 1, 0x80, 0x15D})
        {
            for (int bit = 0; bit < 8; bit++)
            {
                byte[] mutated = base.clone();
                mutated[index] ^= (byte) (1 << bit);
                assertThat(actual(mutated))
                        .as("flipping bit %d of byte 0x%X must change the CRC", bit, index)
                        .isNotEqualTo(baseline);
            }
        }
    }

    @Test
    @DisplayName("leading zero bytes change the result (init is non-zero)")
    void leadingZerosAreNotTransparent()
    {
        // With init == 0 (the XMODEM variant this library previously implemented) a run of
        // leading zero bytes is a no-op. With init == 0xFFFF it is not. This test fails
        // against the pre-fix implementation even though the check vectors alone might not.
        assertThat(actual(new byte[]{0x00, 0x00, 0x12}))
                .isNotEqualTo(actual(new byte[]{0x12}));
    }
}

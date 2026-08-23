package io.github.turtleisaac.nds4j.spec;

import io.github.turtleisaac.nds4j.framework.MemBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Laws that {@link MemBuf} must satisfy to be a correct binary codec, expressed as algebraic
 * properties rather than as recordings of current behaviour.
 * <p>
 * The two properties that matter for a format library are:
 * <ol>
 *   <li><strong>Round-trip identity</strong> &mdash; {@code read(write(v)) == v} for every value in
 *       the type's domain, including the boundaries where sign extension goes wrong.</li>
 *   <li><strong>Byte-order conformance</strong> &mdash; the bytes actually produced must match the
 *       little-endian layout the NDS formats are defined in. Round-trip alone cannot catch an
 *       endianness error, because a symmetric mistake in both directions cancels out.</li>
 * </ol>
 */
@DisplayName("MemBuf satisfies the binary codec laws")
class MemBufAlgebraTest
{
    /** Values that straddle every sign boundary an 8-bit field can have. */
    private static final int[] BYTE_DOMAIN = {0x00, 0x01, 0x7F, 0x80, 0xFE, 0xFF};
    /** Likewise for 16-bit. */
    private static final int[] SHORT_DOMAIN = {0x0000, 0x0001, 0x7FFF, 0x8000, 0xFFFE, 0xFFFF};
    /** Likewise for 32-bit. */
    private static final long[] INT_DOMAIN = {0L, 1L, 0x7FFFFFFFL, 0x80000000L, 0xFFFFFFFEL, 0xFFFFFFFFL};

    private static MemBuf.MemBufReader readerOver(byte[] bytes)
    {
        MemBuf buf = MemBuf.create();
        buf.writer().write(bytes);
        return buf.reader();
    }

    @Nested
    @DisplayName("round-trip identity")
    class RoundTrip
    {
        @Test
        @DisplayName("unsigned 8-bit values survive write then read over the whole domain")
        void uint8()
        {
            for (int v : BYTE_DOMAIN)
            {
                MemBuf buf = MemBuf.create();
                buf.writer().writeByte((byte) v);
                assertThat(buf.reader().readUInt8() & 0xFFFF)
                        .as("readUInt8 after writeByte(0x%02X)", v).isEqualTo(v);
            }
        }

        @Test
        @DisplayName("unsigned 16-bit values survive write then read over the whole domain")
        void uint16()
        {
            for (int v : SHORT_DOMAIN)
            {
                MemBuf buf = MemBuf.create();
                buf.writer().writeShort((short) v);
                assertThat(buf.reader().readUInt16())
                        .as("readUInt16 after writeShort(0x%04X)", v).isEqualTo(v);
            }
        }

        @Test
        @DisplayName("unsigned 32-bit values survive write then read over the whole domain")
        void uint32()
        {
            for (long v : INT_DOMAIN)
            {
                MemBuf buf = MemBuf.create();
                buf.writer().writeInt((int) v);
                assertThat(buf.reader().readUInt32())
                        .as("readUInt32 after writeInt(0x%08X)", (int) v).isEqualTo(v);
            }
        }

        @Test
        @DisplayName("arbitrary byte sequences survive write then read")
        void arbitraryBytes()
        {
            Random rng = new Random(1234);
            for (int trial = 0; trial < 200; trial++)
            {
                byte[] original = new byte[rng.nextInt(300)];
                rng.nextBytes(original);
                MemBuf buf = MemBuf.create();
                buf.writer().write(original);
                assertThat(buf.reader().readBytes(original.length)).isEqualTo(original);
            }
        }

        @Test
        @DisplayName("a sub-range write copies that exact sub-range")
        void subRangeWrite()
        {
            // write(src, srcPos, length) is defined as System.arraycopy semantics:
            // the result is exactly src[srcPos .. srcPos+length).
            Random rng = new Random(99);
            for (int trial = 0; trial < 200; trial++)
            {
                byte[] src = new byte[1 + rng.nextInt(64)];
                rng.nextBytes(src);
                int srcPos = rng.nextInt(src.length);
                int length = rng.nextInt(src.length - srcPos + 1);

                MemBuf buf = MemBuf.create();
                buf.writer().write(src, srcPos, length);

                byte[] expected = java.util.Arrays.copyOfRange(src, srcPos, srcPos + length);
                assertThat(buf.reader().getBuffer()).isEqualTo(expected);
            }
        }
    }

    @Nested
    @DisplayName("byte-order conformance (NDS formats are little-endian)")
    class ByteOrder
    {
        @Test
        @DisplayName("a 16-bit write emits low byte first")
        void shortIsLittleEndian()
        {
            MemBuf buf = MemBuf.create();
            buf.writer().writeShort((short) 0x1234);
            assertThat(buf.reader().getBuffer())
                    .as("0x1234 little-endian is 34 12")
                    .containsExactly(0x34, 0x12);
        }

        @Test
        @DisplayName("a 32-bit write emits least-significant byte first")
        void intIsLittleEndian()
        {
            MemBuf buf = MemBuf.create();
            buf.writer().writeInt(0x12345678);
            assertThat(buf.reader().getBuffer())
                    .as("0x12345678 little-endian is 78 56 34 12")
                    .containsExactly(0x78, 0x56, 0x34, 0x12);
        }

        @Test
        @DisplayName("reads interpret bytes as little-endian")
        void readsAreLittleEndian()
        {
            assertThat(readerOver(new byte[]{0x34, 0x12}).readUInt16()).isEqualTo(0x1234);
            assertThat(readerOver(new byte[]{0x78, 0x56, 0x34, 0x12}).readUInt32()).isEqualTo(0x12345678L);
            // High bit set: the case where a signed shift would corrupt the value.
            assertThat(readerOver(new byte[]{0x00, 0x00, 0x00, (byte) 0x80}).readUInt32())
                    .isEqualTo(0x80000000L);
        }
    }

    @Nested
    @DisplayName("readByte contract")
    class ReadByteContract
    {
        @Test
        @DisplayName("returns an unsigned value in [0,255] for every possible byte")
        void isUnsigned()
        {
            // Downstream code depends on knowing which convention this uses. Pin it down:
            // readByte is unsigned, matching Buffer.readByte and readUInt8. Callers wanting a
            // signed field cast explicitly.
            for (int v = 0; v <= 0xFF; v++)
            {
                MemBuf buf = MemBuf.create();
                buf.writer().writeByte((byte) v);
                assertThat(buf.reader().readByte())
                        .as("readByte of 0x%02X", v)
                        .isBetween(0, 255)
                        .isEqualTo(v);
            }
        }

        @Test
        @DisplayName("casting the result to byte recovers the signed interpretation")
        void castRecoversSigned()
        {
            for (int v = 0; v <= 0xFF; v++)
            {
                MemBuf buf = MemBuf.create();
                buf.writer().writeByte((byte) v);
                assertThat((byte) buf.reader().readByte()).isEqualTo((byte) v);
            }
        }
    }

    @Nested
    @DisplayName("alignment obeys its mathematical definition")
    class Alignment
    {
        /**
         * Aligning a position p to a boundary n is defined as the smallest q with
         * q >= p and q mod n == 0. Two consequences follow, and both are asserted:
         * an already-aligned position is a fixed point, and alignment never advances
         * by a whole block.
         */
        @Test
        @DisplayName("align(n) is the least multiple of n at or after the current position")
        void alignDefinition()
        {
            for (int alignment : new int[]{2, 4, 8, 16, 512})
            {
                for (int start = 0; start < 3 * alignment; start++)
                {
                    MemBuf buf = MemBuf.create();
                    buf.writer().write(new byte[start]);
                    buf.writer().align(alignment);
                    int q = buf.writer().getPosition();

                    assertThat(q % alignment)
                            .as("align(%d) from %d must land on a multiple", alignment, start)
                            .isZero();
                    assertThat(q)
                            .as("align(%d) from %d must not move backwards", alignment, start)
                            .isGreaterThanOrEqualTo(start);
                    assertThat(q - start)
                            .as("align(%d) from %d must advance less than a full block", alignment, start)
                            .isLessThan(alignment);
                }
            }
        }

        @Test
        @DisplayName("align is idempotent")
        void alignIsIdempotent()
        {
            for (int alignment : new int[]{2, 4, 512})
            {
                for (int start = 0; start < 2 * alignment; start++)
                {
                    MemBuf buf = MemBuf.create();
                    buf.writer().write(new byte[start]);
                    buf.writer().align(alignment);
                    int once = buf.writer().getPosition();
                    buf.writer().align(alignment);
                    assertThat(buf.writer().getPosition())
                            .as("aligning twice must equal aligning once")
                            .isEqualTo(once);
                }
            }
        }
    }

    @Nested
    @DisplayName("fixed-width string fields")
    class FixedWidthStrings
    {
        @Test
        @DisplayName("always occupy exactly the declared width")
        void exactWidth()
        {
            // A fixed-width field is a contract about size, so neither a short nor an
            // over-long value may change how many bytes are emitted.
            for (String s : new String[]{"", "A", "ABC", "ABCD", "ABCDEFGHIJKL", "ABCDEFGHIJKLMNOP"})
            {
                MemBuf buf = MemBuf.create();
                buf.writer().writeString(s, 4);
                assertThat(buf.reader().getBuffer())
                        .as("writeString(\"%s\", 4) must emit 4 bytes", s)
                        .hasSize(4);
            }
        }

        @Test
        @DisplayName("truncate rather than overrun, and pad rather than under-fill")
        void truncateAndPad()
        {
            MemBuf over = MemBuf.create();
            over.writer().writeString("ABCDEF", 4);
            assertThat(new String(over.reader().getBuffer(), StandardCharsets.ISO_8859_1))
                    .isEqualTo("ABCD");

            MemBuf under = MemBuf.create();
            under.writer().writeString("AB", 4);
            assertThat(under.reader().getBuffer()).containsExactly('A', 'B', 0, 0);
        }

        @Test
        @DisplayName("readString inverts writeString for every byte value")
        void stringRoundTrip()
        {
            // The write path encodes ISO-8859-1 (one byte per char, transparent to raw bytes).
            // Any read charset that is not the same mapping silently destroys bytes >= 0x80,
            // which is exactly what a UTF-8 read path did.
            StringBuilder sb = new StringBuilder();
            for (int c = 0; c < 256; c++) sb.append((char) c);
            String all = sb.toString();

            MemBuf buf = MemBuf.create();
            buf.writer().writeString(all);
            assertThat(buf.reader().readString(256)).isEqualTo(all);
        }
    }

    @Nested
    @DisplayName("buffer growth")
    class Growth
    {
        @Test
        @DisplayName("skipping past capacity zero-fills rather than corrupting or throwing")
        void skipGrows()
        {
            MemBuf buf = MemBuf.create();
            buf.writer().skip(200_000);
            buf.writer().writeByte((byte) 0x7F);
            byte[] out = buf.reader().getBuffer();
            assertThat(out).hasSize(200_001);
            assertThat(out[200_000]).isEqualTo((byte) 0x7F);
            assertThat(out[0]).isZero();
            assertThat(out[199_999]).isZero();
        }

        @Test
        @DisplayName("large sequential writes do not lose data")
        void largeWrite()
        {
            // Guards the growth strategy: whatever the resize policy, content must survive it.
            byte[] chunk = new byte[4096];
            new Random(5).nextBytes(chunk);
            MemBuf buf = MemBuf.create();
            for (int i = 0; i < 64; i++) buf.writer().write(chunk);

            byte[] out = buf.reader().getBuffer();
            assertThat(out).hasSize(64 * chunk.length);
            for (int i = 0; i < 64; i++)
                assertThat(java.util.Arrays.copyOfRange(out, i * chunk.length, (i + 1) * chunk.length))
                        .as("chunk %d survived growth", i).isEqualTo(chunk);
        }

        @Test
        @DisplayName("reading beyond written data fails loudly, not silently")
        void overreadIsDetected()
        {
            // A truncated file must produce a diagnosable error rather than zero-filled garbage.
            MemBuf buf = MemBuf.create();
            buf.writer().writeByte((byte) 1);
            assertThatCode(() -> buf.reader().readBytes(64))
                    .as("over-read must throw")
                    .isInstanceOf(RuntimeException.class);
        }
    }
}

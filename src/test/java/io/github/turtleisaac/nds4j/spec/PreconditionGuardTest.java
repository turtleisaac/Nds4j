package io.github.turtleisaac.nds4j.spec;

import io.github.turtleisaac.nds4j.framework.MemBuf;
import io.github.turtleisaac.nds4j.images.IndexedImage;
import io.github.turtleisaac.nds4j.images.Palette;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Preconditions on the public API.
 * <p>
 * Every argument here is one a caller can plausibly pass by mistake &mdash; a dimension read from
 * a corrupt header, a size computed as a difference that came out negative, a palette that a
 * lookup returned as {@code null}. The distinction being asserted is between <em>rejected with an
 * explanation</em> and <em>accepted, then failing somewhere unrelated</em>.
 * <p>
 * A bare {@link NegativeArraySizeException} thrown from an allocation deep inside a constructor
 * satisfies neither: it does not name the offending argument, and it points the reader at the
 * wrong line. So these tests assert both that the call fails and that the failure carries a
 * message naming what was wrong.
 */
@DisplayName("Public entry points reject nonsensical arguments with an explanation")
class PreconditionGuardTest
{
    private static void assertRejectedWithMessage(String what, org.assertj.core.api.ThrowableAssert.ThrowingCallable call)
    {
        assertThatThrownBy(call)
                .as("%s must be rejected", what)
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(NegativeArraySizeException.class)
                .isNotInstanceOf(NullPointerException.class)
                .isNotInstanceOf(ArrayIndexOutOfBoundsException.class)
                .hasMessageMatching(".*\\S.*");
    }

    @Test
    @DisplayName("an image with a non-positive dimension is rejected")
    void nonPositiveImageDimensions()
    {
        // 0 passes a "multiple of 8" test, so without an explicit check it produces a canvas
        // with no pixels that only fails much later, at save time.
        assertRejectedWithMessage("a 0x0 image", () -> new IndexedImage(0, 0, 4, new Palette(16)));
        assertRejectedWithMessage("a zero-height image", () -> new IndexedImage(0, 16, 4, new Palette(16)));
        assertRejectedWithMessage("a zero-width image", () -> new IndexedImage(16, 0, 4, new Palette(16)));
        assertRejectedWithMessage("a negative-height image", () -> new IndexedImage(-8, 16, 4, new Palette(16)));
        assertRejectedWithMessage("a negative-width image", () -> new IndexedImage(16, -8, 4, new Palette(16)));
    }

    @Test
    @DisplayName("an image without a palette is rejected at construction")
    void nullPaletteRejected()
    {
        // Accepting null defers the failure to the first render or save, far from the caller
        // that actually supplied it.
        assertRejectedWithMessage("an image with a null palette", () -> new IndexedImage(16, 16, 4, null));
    }

    @Test
    @DisplayName("a non-positive palette size is rejected")
    void nonPositivePaletteSize()
    {
        assertRejectedWithMessage("an empty palette", () -> new Palette(0));
        assertRejectedWithMessage("a negative palette", () -> new Palette(-16));
    }

    @Test
    @DisplayName("negative sizes are rejected by the buffer rather than by the JVM")
    void negativeSizes()
    {
        assertRejectedWithMessage("reading a negative count",
                () -> MemBuf.create().reader().readBytes(-5));
        assertRejectedWithMessage("a negative-width string field",
                () -> MemBuf.create().writer().writeString("x", -1));
    }

    @Test
    @DisplayName("skipping backwards is rejected, because it would silently truncate")
    void negativeSkip()
    {
        // A negative skip rewinds the write cursor. Since the cursor doubles as the buffer's
        // logical end, everything written past the new position simply disappears on read --
        // with no error at any point.
        assertRejectedWithMessage("a negative skip", () -> MemBuf.create().writer().skip(-5));
    }

    @Test
    @DisplayName("legitimate boundary arguments are still accepted")
    void validBoundariesStillWork()
    {
        // Guards must not overreach: the smallest legal image, a zero-length read, a
        // zero-length skip and a zero-width string field are all meaningful.
        assertThatCode(() -> new IndexedImage(8, 8, 4, new Palette(16))).doesNotThrowAnyException();
        assertThatCode(() -> new Palette(1)).doesNotThrowAnyException();
        assertThatCode(() -> MemBuf.create().reader().readBytes(0)).doesNotThrowAnyException();
        assertThatCode(() -> MemBuf.create().writer().skip(0)).doesNotThrowAnyException();
        assertThatCode(() -> MemBuf.create().writer().writeString("", 0)).doesNotThrowAnyException();
    }
}

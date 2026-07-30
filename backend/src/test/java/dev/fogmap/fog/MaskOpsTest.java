package dev.fogmap.fog;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MaskOpsTest {

    @Test
    @DisplayName("popcount считает установленные биты")
    void popCount() {
        assertEquals(0, MaskOps.popCount(new byte[8192]));

        byte[] mask = new byte[8192];
        mask[0] = (byte) 0xFF;
        mask[100] = 0b0000_0101;
        assertEquals(10, MaskOps.popCount(mask));
    }

    @Test
    @DisplayName("слияние объединяет биты и не трогает аргументы")
    void orMerges() {
        byte[] a = new byte[8192];
        byte[] b = new byte[8192];
        a[0] = 0b0000_1100;
        b[0] = 0b0000_0110;

        byte[] merged = MaskOps.or(a, b);

        assertEquals(0b0000_1110, merged[0]);
        assertEquals(0b0000_1100, a[0]);
        assertEquals(0b0000_0110, b[0]);
    }

    @Test
    @DisplayName("слияние идемпотентно и коммутативно")
    void orIsIdempotentAndCommutative() {
        byte[] a = new byte[8192];
        byte[] b = new byte[8192];
        a[10] = (byte) 0xA5;
        b[10] = (byte) 0x5A;
        b[20] = (byte) 0xFF;

        byte[] ab = MaskOps.or(a, b);
        byte[] ba = MaskOps.or(b, a);

        assertArrayEquals(ab, ba);
        assertArrayEquals(ab, MaskOps.or(ab, a));
        assertArrayEquals(ab, MaskOps.or(ab, b));
    }
}

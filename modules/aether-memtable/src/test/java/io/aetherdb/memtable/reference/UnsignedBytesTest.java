package io.aetherdb.memtable.reference;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import org.junit.jupiter.api.Test;

class UnsignedBytesTest {
    @Test
    void ordersUnsignedBytesAndPrefixes() {
        assertThat(UnsignedBytes.compare(new byte[0], new byte[] {0})).isNegative();
        assertThat(UnsignedBytes.compare(new byte[] {0}, new byte[] {0, 0})).isNegative();
        assertThat(UnsignedBytes.compare(new byte[] {0, (byte) 0xff}, new byte[] {1})).isNegative();
        assertThat(UnsignedBytes.compare(new byte[] {0x7f}, new byte[] {(byte) 0x80})).isNegative();
        assertThat(UnsignedBytes.compare(new byte[] {(byte) 0x80}, new byte[] {(byte) 0xff})).isNegative();
    }

    @Test
    void comparatorPropertiesHoldForGeneratedInputs() {
        Random random = new Random(0xA37E_0001L);
        for (int iteration = 0; iteration < 10_000; iteration++) {
            byte[] left = bytes(random);
            byte[] middle = bytes(random);
            byte[] right = bytes(random);
            int forward = Integer.signum(UnsignedBytes.compare(left, middle));
            int backward = Integer.signum(UnsignedBytes.compare(middle, left));
            assertThat(forward).isEqualTo(-backward);
            if (UnsignedBytes.compare(left, middle) <= 0 && UnsignedBytes.compare(middle, right) <= 0) {
                assertThat(UnsignedBytes.compare(left, right)).isLessThanOrEqualTo(0);
            }
        }
    }

    private static byte[] bytes(Random random) {
        byte[] result = new byte[random.nextInt(8)];
        random.nextBytes(result);
        return result;
    }
}

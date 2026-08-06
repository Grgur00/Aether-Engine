package io.aetherdb.embedded.typed;

import static org.assertj.core.api.Assertions.assertThat;

import io.aetherdb.codec.generated.GeneratedCodecs;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class GeneratedScalarCoverageTest {
    @Test void allExtendedScalarTypesRoundTripCanonically() {
        ScalarMatrix expected = new ScalarMatrix(
                Byte.MIN_VALUE,
                Short.MIN_VALUE,
                Float.NaN,
                '\uffff',
                LocalDate.of(1900, 1, 2),
                LocalTime.of(23, 59, 59, 999_999_999),
                LocalDateTime.of(2040, 2, 29, 12, 13, 14, 15),
                Duration.ofSeconds(-12, 345),
                new BigInteger("-123456789012345678901234567890"),
                new BigDecimal("-1234567890.012300"),
                new byte[] {0, 1, -1, 42});
        var codec = GeneratedCodecs.forRecord(ScalarMatrix.class);

        byte[] first = codec.encode(expected), second = codec.encode(expected);
        ScalarMatrix actual = codec.decode(1, first);

        assertThat(second).isEqualTo(first);
        assertThat(actual.byteValue()).isEqualTo(expected.byteValue());
        assertThat(actual.shortValue()).isEqualTo(expected.shortValue());
        assertThat(actual.floatValue()).isNaN();
        assertThat(actual.charValue()).isEqualTo(expected.charValue());
        assertThat(actual.date()).isEqualTo(expected.date());
        assertThat(actual.time()).isEqualTo(expected.time());
        assertThat(actual.dateTime()).isEqualTo(expected.dateTime());
        assertThat(actual.duration()).isEqualTo(expected.duration());
        assertThat(actual.integer()).isEqualTo(expected.integer());
        assertThat(actual.decimal()).isEqualTo(expected.decimal());
        assertThat(actual.bytes()).isEqualTo(expected.bytes());
    }
}

package io.aetherdb.codec.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Bounds the encoded UTF-8 bytes of a variable-length field. */
@Target(ElementType.RECORD_COMPONENT)
@Retention(RetentionPolicy.CLASS)
public @interface AetherMaxLength {
    /**
     * Returns the maximum UTF-8 byte length or collection element count, as applicable.
     *
     * @return positive upper bound
     */
    int value();
}

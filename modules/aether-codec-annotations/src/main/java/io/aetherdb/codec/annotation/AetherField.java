package io.aetherdb.codec.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Assigns a stable persistent identity to a record component. */
@Target(ElementType.RECORD_COMPONENT)
@Retention(RetentionPolicy.CLASS)
public @interface AetherField {
    /**
     * Returns the durable wire ID, or zero when the schema tool should assign it.
     *
     * @return positive field ID or zero
     */
    int id() default 0;

    /**
     * Identifies the field's name in the immediately preceding schema version.
     *
     * @return previous component name, or an empty string when the field was not renamed
     */
    String previousName() default "";

    /**
     * Indicates whether the field may be absent from older encoded payloads.
     *
     * @return {@code true} when omission is permitted
     */
    boolean optional() default false;

    /**
     * Indicates whether the encoded field may explicitly contain {@code null}.
     *
     * @return {@code true} when null values are permitted
     */
    boolean nullable() default false;
}

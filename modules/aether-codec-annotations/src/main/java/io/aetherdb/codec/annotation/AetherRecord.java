package io.aetherdb.codec.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares one durable record schema family and writer version. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface AetherRecord {
    /**
     * Returns the durable schema UUID, or an empty string when the schema tool should assign it.
     *
     * @return canonical UUID text or an empty string
     */
    String schemaId() default "";

    /**
     * Returns the positive schema version represented by the annotated record.
     *
     * @return schema version
     */
    int version();
}

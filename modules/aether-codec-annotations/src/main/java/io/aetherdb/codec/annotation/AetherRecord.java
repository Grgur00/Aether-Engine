package io.aetherdb.codec.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares one durable record schema family and writer version. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface AetherRecord {
    String schemaId() default "";
    int version();
}

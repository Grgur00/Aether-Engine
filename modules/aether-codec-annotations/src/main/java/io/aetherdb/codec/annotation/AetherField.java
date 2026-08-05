package io.aetherdb.codec.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Assigns a stable persistent identity to a record component. */
@Target(ElementType.RECORD_COMPONENT)
@Retention(RetentionPolicy.CLASS)
public @interface AetherField {
    int id() default 0;
    String previousName() default "";
    boolean optional() default false;
    boolean nullable() default false;
}

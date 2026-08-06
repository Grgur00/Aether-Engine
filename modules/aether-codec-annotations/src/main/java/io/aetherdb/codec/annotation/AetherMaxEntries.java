package io.aetherdb.codec.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Sets the maximum decoded element or entry count for a persistent container field. */
@Target(ElementType.RECORD_COMPONENT)
@Retention(RetentionPolicy.CLASS)
public @interface AetherMaxEntries {
    /** Returns the positive maximum accepted container count. */
    int value();
}

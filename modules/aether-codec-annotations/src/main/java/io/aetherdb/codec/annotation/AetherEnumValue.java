package io.aetherdb.codec.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Assigns a stable positive wire value to one {@link AetherEnum} constant. */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.CLASS)
public @interface AetherEnumValue {
    /** Returns the stable positive numeric identity. */
    int value();
}

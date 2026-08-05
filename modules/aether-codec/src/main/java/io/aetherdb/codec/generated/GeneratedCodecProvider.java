package io.aetherdb.codec.generated;

import io.aetherdb.api.typed.ValueCodec;

/** Runtime registration emitted by the Aether record annotation processor. */
public interface GeneratedCodecProvider {
    /** Returns the Java record served by this provider.
     * @return record class */
    Class<?> recordType();
    /** Returns the generated record codec.
     * @return immutable codec instance */
    ValueCodec<?> codec();
}

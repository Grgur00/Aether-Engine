package io.aetherdb.codec.generated;

import io.aetherdb.api.typed.ValueCodec;

/** Runtime registration emitted by the Aether record annotation processor. */
public interface GeneratedCodecProvider {
    Class<?> recordType();
    ValueCodec<?> codec();
}

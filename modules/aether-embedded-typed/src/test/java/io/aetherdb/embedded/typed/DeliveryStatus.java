package io.aetherdb.embedded.typed;

import io.aetherdb.codec.annotation.AetherEnum;
import io.aetherdb.codec.annotation.AetherEnumValue;

/** Stable-number enum fixture; source order and names are not its wire identity. */
@AetherEnum
public enum DeliveryStatus {
    @AetherEnumValue(10)
    CREATED,
    @AetherEnumValue(20)
    IN_TRANSIT,
    @AetherEnumValue(30)
    DELIVERED
}

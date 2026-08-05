package io.aetherdb.api.typed;
import java.util.UUID;
public interface ValueCodec<V>{UUID schemaId();int currentSchemaVersion();int maximumEncodedSize(V value);byte[] fingerprint();byte[] encode(V value);V decode(int schemaVersion,byte[] encoded);}

package io.aetherdb.api.typed;
public interface KeyCodec<K>{String codecId();int encodingVersion();int maximumEncodedSize();byte[] fingerprint();byte[] encode(K value);K decode(byte[] encoded);}

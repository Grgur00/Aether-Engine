package io.aetherdb.client.api;

public final class ClientProtocol {
    public static final int LEADER_QUERY=16_384, CLIENT_WRITE=16_385, CLIENT_GET=16_386,
            SCAN_OPEN=16_387, SCAN_NEXT=16_388, SCAN_CLOSE=16_389, READ_INDEX=8_195;
    private ClientProtocol() {}
}

package io.aetherdb.client.api;

/** Stable RPC operation codes exposed by the client protocol. */
public final class ClientProtocol {
    /** Discovers the current leader. */ public static final int LEADER_QUERY=16_384;
    /** Submits an idempotent write command. */ public static final int CLIENT_WRITE=16_385;
    /** Performs a point read. */ public static final int CLIENT_GET=16_386;
    /** Opens a server-side scan cursor. */ public static final int SCAN_OPEN=16_387;
    /** Fetches the next scan page. */ public static final int SCAN_NEXT=16_388;
    /** Closes a scan cursor. */ public static final int SCAN_CLOSE=16_389;
    /** Establishes a linearizable read barrier. */ public static final int READ_INDEX=8_195;
    private ClientProtocol() {}
}

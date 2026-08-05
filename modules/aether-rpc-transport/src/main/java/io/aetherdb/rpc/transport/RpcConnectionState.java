package io.aetherdb.rpc.transport;

/** Observable lifecycle state of one RPC transport connection. */
public enum RpcConnectionState {
    /** Connection object exists but no network attempt has started. */ NEW,
    /** Socket connection is in progress. */ CONNECTING,
    /** Peers are authenticating and negotiating TLS. */ TLS_HANDSHAKING,
    /** Peers are exchanging protocol capabilities and identities. */ HELLO_EXCHANGE,
    /** Competing connections between the same peers are being resolved. */ DUPLICATE_RESOLUTION,
    /** Connection accepts new RPC streams. */ READY,
    /** Existing streams may finish but new streams are rejected. */ DRAINING,
    /** Transport resources are being released. */ CLOSING,
    /** Connection is fully closed. */ CLOSED,
    /** Connection terminated because of a transport or protocol failure. */ FAILED
}

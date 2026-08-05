package io.aetherdb.cluster.api;

import java.net.IDN;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

public final class ClusterEndpoint implements Comparable<ClusterEndpoint> {
    public enum Scheme { AETHER_TLS(1), AETHER_TCP_DEVELOPMENT(2); private final int code; Scheme(int c){code=c;} public int code(){return code;} }
    public enum AddressType { IPV4(1), IPV6(2), DNS(3); private final int code; AddressType(int c){code=c;} public int code(){return code;} }
    public static final int INTERNAL_RPC = 1;
    public static final int CLIENT_RPC = 2;
    private final Scheme scheme;
    private final AddressType addressType;
    private final byte[] address;
    private final int port;
    private final int priority;
    private final int flags;

    public static ClusterEndpoint of(Scheme scheme, String host, int port, int priority, int flags) {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("host is blank");
        if (host.equals("0.0.0.0") || host.equals("::") || host.equals("*")) throw new IllegalArgumentException("wildcard host is not advertisable");
        String candidate = host;
        if (candidate.startsWith("[") && candidate.endsWith("]")) candidate = candidate.substring(1, candidate.length() - 1);
        try {
            if (candidate.indexOf(':') >= 0) return new ClusterEndpoint(scheme, AddressType.IPV6, InetAddress.getByName(candidate).getAddress(), port, priority, flags);
            if (candidate.matches("[0-9]+(?:\\.[0-9]+){3}")) {
                String[] parts = candidate.split("\\.");
                byte[] bytes = new byte[4];
                for (int i=0;i<4;i++) { int v=Integer.parseInt(parts[i]); if(v>255) throw new IllegalArgumentException("invalid IPv4 host"); bytes[i]=(byte)v; }
                return new ClusterEndpoint(scheme, AddressType.IPV4, bytes, port, priority, flags);
            }
        } catch (UnknownHostException e) { throw new IllegalArgumentException("invalid IP host", e); }
        String dns = IDN.toASCII(candidate, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        while (dns.endsWith(".")) dns = dns.substring(0, dns.length()-1);
        return new ClusterEndpoint(scheme, AddressType.DNS, dns.getBytes(StandardCharsets.US_ASCII), port, priority, flags);
    }

    public ClusterEndpoint(Scheme scheme, AddressType type, byte[] address, int port, int priority, int flags) {
        if (scheme == null || type == null || address == null) throw new IllegalArgumentException("endpoint fields are required");
        if (port < 1 || port > 65535 || priority < 0 || priority > 65535) throw new IllegalArgumentException("invalid port or priority");
        if ((flags & ~(INTERNAL_RPC|CLIENT_RPC)) != 0 || flags == 0) throw new IllegalArgumentException("invalid endpoint flags");
        int required = type == AddressType.IPV4 ? 4 : type == AddressType.IPV6 ? 16 : -1;
        if ((required > 0 && address.length != required) || (type == AddressType.DNS && (address.length < 1 || address.length > 255))) throw new IllegalArgumentException("invalid address length");
        this.scheme=scheme; this.addressType=type; this.address=address.clone(); this.port=port; this.priority=priority; this.flags=flags;
    }
    public Scheme scheme(){return scheme;} public AddressType addressType(){return addressType;} public byte[] address(){return address.clone();}
    public int port(){return port;} public int priority(){return priority;} public int flags(){return flags;}
    @Override public int compareTo(ClusterEndpoint other) { return COMPARATOR.compare(this, other); }
    public static final Comparator<ClusterEndpoint> COMPARATOR = Comparator.comparingInt(ClusterEndpoint::priority).thenComparingInt(e->e.scheme.code()).thenComparingInt(e->e.addressType.code()).thenComparing(ClusterEndpoint::address, Arrays::compareUnsigned).thenComparingInt(ClusterEndpoint::port).thenComparingInt(ClusterEndpoint::flags);
    @Override public boolean equals(Object o){return o instanceof ClusterEndpoint e && compareTo(e)==0;}
    @Override public int hashCode(){return (((((scheme.hashCode()*31+addressType.hashCode())*31+Arrays.hashCode(address))*31+port)*31+priority)*31+flags);}
}

package io.aetherdb.client.codec;

import static org.assertj.core.api.Assertions.*;
import io.aetherdb.client.api.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ClientWriteCodecV1Test {
 @Test void orderedWriteRoundTrips(){ var request=new ClientWriteRequest(7,List.of(new ClientWriteOperation(ClientWriteOperation.Type.PUT,bytes("profile/1/name"),bytes("Ada")),new ClientWriteOperation(ClientWriteOperation.Type.DELETE,bytes("profile/1/draft"),new byte[0]))); byte[] encoded=ClientWriteCodecV1.encode(request); assertThat(encoded).hasSize(128+16+14+3+16+15); var decoded=ClientWriteCodecV1.decode(encoded); assertThat(decoded.configurationVersion()).isEqualTo(7); assertThat(decoded.operations()).hasSize(2); assertThat(decoded.operations().get(0).value()).isEqualTo(bytes("Ada")); assertThat(decoded.operations().get(1).type()).isEqualTo(ClientWriteOperation.Type.DELETE); }
 @Test void corruptionIsRejected(){ byte[] encoded=ClientWriteCodecV1.encode(new ClientWriteRequest(0,List.of(new ClientWriteOperation(ClientWriteOperation.Type.PUT,bytes("k"),bytes("v"))))); encoded[130]^=1; assertThatThrownBy(()->ClientWriteCodecV1.decode(encoded)).isInstanceOf(IllegalArgumentException.class); }
 private static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
}

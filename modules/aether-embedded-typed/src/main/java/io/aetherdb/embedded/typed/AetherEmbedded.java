package io.aetherdb.embedded.typed;
import io.aetherdb.api.AetherDatabase;import io.aetherdb.api.typed.TypedAetherDatabase;import io.aetherdb.engine.Aether;import java.nio.file.Path;
public final class AetherEmbedded{private AetherEmbedded(){}public static TypedAetherDatabase openInMemory(){return adapt(Aether.openInMemory());}public static TypedAetherDatabase open(Path directory){return adapt(Aether.open(directory));}public static TypedAetherDatabase adapt(AetherDatabase database){return new EmbeddedTypedDatabase(database,true);}}

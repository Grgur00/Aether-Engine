package io.aetherdb.examples.social;

import io.aetherdb.api.typed.*;
import io.aetherdb.codec.BuiltInKeyCodecs;
import java.util.*;

/** Domain repository backed by one typed, ordered Aether collection. */
public final class SocialProfileRepository {
    public static final CollectionDefinition<String,UserProfile> PROFILES = new CollectionDefinition<>(
            CollectionId.of("7a39f7c1-d995-4b08-a866-4526e175e94f"), "social-profiles",
            BuiltInKeyCodecs.utf8String(), UserProfileCodec.INSTANCE,
            Set.of(CollectionCapability.POINT_READ, CollectionCapability.POINT_WRITE,
                    CollectionCapability.RANGE_SCAN, CollectionCapability.SNAPSHOT_READ));

    private final TypedAetherCollection<String,UserProfile> profiles;
    public SocialProfileRepository(TypedAetherDatabase database) { profiles=Objects.requireNonNull(database).collection(PROFILES); }
    private SocialProfileRepository(TypedAetherCollection<String,UserProfile> profiles) { this.profiles=profiles; }

    public TypedWriteResult save(UserProfile profile) { return profiles.put(profile.id(),profile); }
    public TypedWriteResult saveAll(TypedAetherDatabase database,UserProfile... values){TypedWriteBatch batch=database.batch();for(UserProfile value:values)batch.put(profiles,value.id(),value);return database.write(batch);}
    public Optional<UserProfile> find(String id) { return profiles.get(id).value(); }
    public List<UserProfile> findAll() { return profiles.scanAll().stream().map(TypedKeyValue::value).toList(); }
    public TypedWriteResult delete(String id) { return profiles.delete(id); }
    public SocialProfileRepository at(TypedAetherSnapshot snapshot){return new SocialProfileRepository(snapshot.collection(PROFILES));}
}

package io.aetherdb.examples.social;

import io.aetherdb.embedded.typed.AetherEmbedded;
import java.time.Instant;

/** Runnable social-profile service using Aether's typed embedded API. */
public final class SocialNetworkApplication {
    private SocialNetworkApplication() {}
    public static void main(String[] arguments) {
        try (var database=AetherEmbedded.openInMemory()) {
            SocialProfileRepository profiles=new SocialProfileRepository(database);
            UserProfile ada=new UserProfile("usr-1001","ada.codes","Ada Lovelace","ada@example.test","Mathematics, analytical engines, and computing.","London, UK",128_450,true,Instant.parse("2024-01-15T10:30:00Z"));
            UserProfile grace=new UserProfile("usr-1002","grace.debugs","Grace Hopper","grace@example.test","Compiler enthusiast. Debugging since the moth.","New York, US",94_210,true,Instant.parse("2024-02-20T14:15:00Z"));
            profiles.saveAll(database,ada,grace);
            try (var beforeGrowth=database.snapshot()) {
                profiles.save(new UserProfile(ada.id(),ada.username(),ada.displayName(),ada.email(),ada.bio(),ada.location(),ada.followerCount()+1_550,ada.verified(),ada.createdAt()));
                System.out.println("Ada before campaign: "+profiles.at(beforeGrowth).find(ada.id()).orElseThrow().followerCount()+" followers");
                System.out.println("Ada latest:          "+profiles.find(ada.id()).orElseThrow().followerCount()+" followers");
            }
            System.out.println("Profiles in the social service:");
            profiles.findAll().forEach(profile->System.out.println("  @"+profile.username()+" — "+profile.displayName()));
        }
    }
}

package io.aetherdb.cluster.core;
import java.util.*;
public final class DualMajority {
 private DualMajority(){}
 public static boolean hasMajority(Set<UUID> voters,Set<UUID> acknowledgements){Objects.requireNonNull(voters);Objects.requireNonNull(acknowledgements);if(voters.isEmpty())throw new IllegalArgumentException("voter set is empty");int count=0;for(UUID id:voters)if(acknowledgements.contains(id))count++;return count>=voters.size()/2+1;}
 public static boolean hasJointMajority(Set<UUID> oldVoters,Set<UUID> newVoters,Set<UUID> acknowledgements){return hasMajority(oldVoters,acknowledgements)&&hasMajority(newVoters,acknowledgements);}
 public static long committedIndex(Set<UUID> oldVoters,Set<UUID> newVoters,Map<UUID,Long> durableIndexes){return Math.min(majorityIndex(oldVoters,durableIndexes),majorityIndex(newVoters,durableIndexes));}
 private static long majorityIndex(Set<UUID> voters,Map<UUID,Long> indexes){if(voters.isEmpty())throw new IllegalArgumentException("voter set is empty");long[] values=voters.stream().mapToLong(id->indexes.getOrDefault(id,0L)).sorted().toArray();return values[values.length-(values.length/2+1)];}
}

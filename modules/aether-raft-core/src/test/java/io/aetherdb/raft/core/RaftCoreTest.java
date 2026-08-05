package io.aetherdb.raft.core;
import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;
final class RaftCoreTest {
 @Test void quorumAndCurrentTermCommitRule(){ assertThat(RaftQuorum.required(5)).isEqualTo(3); var tracker=new RaftCommitTracker(); assertThat(tracker.recalculate(List.of(8L,8L,4L),3,index->2)).isZero(); assertThat(tracker.recalculate(List.of(8L,8L,4L),3,index->3)).isEqualTo(8); }
 @Test void followerMatchIsMonotonic(){ var progress=new FollowerProgress(10); progress.matched(7); progress.matched(5); assertThat(progress.matchIndex()).isEqualTo(7); assertThat(progress.nextIndex()).isEqualTo(8); }
}

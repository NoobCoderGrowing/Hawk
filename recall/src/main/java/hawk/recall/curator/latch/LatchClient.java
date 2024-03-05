package hawk.recall.curator.latch;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.leader.LeaderLatch;
import org.apache.curator.framework.recipes.leader.LeaderLatchListener;

public class LatchClient {

    private LeaderLatch leaderLatch;

    public LatchClient(CuratorFramework curatorClient, String zkPath, String id) {
        leaderLatch = new LeaderLatch(curatorClient, zkPath, id);
        leaderLatch.addListener(new LeaderLatchListener() {
            @Override
            public void isLeader() {
                requestIndexUpload();
            }

            @Override
            public void notLeader() {
                syncIndex();
            }
        });
    }

    public void start() throws Exception {
        leaderLatch.start();
    }

    public void stop() throws Exception {
        leaderLatch.close();
//        leaderLatch.close(LeaderLatch.CloseMode.NOTIFY_LEADER);
    }

    public void requestIndexUpload() {
        // do something here
    }

    public void syncIndex() {
        // do something here
    }

}

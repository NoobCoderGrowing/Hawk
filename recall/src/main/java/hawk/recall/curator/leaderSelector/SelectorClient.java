package hawk.recall.curator.leaderSelector;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.leader.LeaderSelector;
import org.apache.curator.framework.recipes.leader.LeaderSelectorListenerAdapter;

public class SelectorClient extends LeaderSelectorListenerAdapter{


    private String name;
    private final LeaderSelector leaderSelector;


    public SelectorClient(CuratorFramework client, String path, String name) {
        this.name = name;
        leaderSelector = new LeaderSelector(client, path, this);
        // for most cases you will want your instance to requeue when it relinquishes leadership
        leaderSelector.autoRequeue();
    }

    public void start() {
        leaderSelector.start();
    }

    public void close() {
        leaderSelector.close();
    }

    @Override
    public void takeLeadership(CuratorFramework client){
        // we are now the leader. This method should not return until we want to relinquish leadership

        while (true){
            System.out.println(name + " is now the leader. ");
        }

    }
}

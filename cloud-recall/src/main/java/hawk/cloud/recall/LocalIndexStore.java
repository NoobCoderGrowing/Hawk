package hawk.cloud.recall;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 本节点的索引存放处 —— 一个 recall 节点只配**一个 role、一个分片**，所以只有**一个**
 * {@link IndexStore}。这个类把它做成 Spring bean，让**收索引的**（{@code IndexReceiveController}）
 * 和**报状态的**（{@code HealthController}）拿到的是同一个实例、同一个目录。
 *
 * <h2>为什么要有这个类</h2>
 * 原先 {@code IndexStore} 是在 {@code IndexReceiveController} 里**懒建**的，原因写在它的注释里：
 * 「role/shardNum 是字段注入、构造时还没有」。那是字段注入的绕法。现在改成构造注入之后，
 * 目录在**启动时**就能算出来，于是 /health 也能读到它 —— 而 /health 报的
 * {@code round} 正是协调器判"这一轮建成了没有"的依据，本来就必须和收索引的是同一个目录。
 *
 * <p>顺带这也是为什么要收成一个 bean 而不是各处各建一个：两个实例指向同一路径也能用，
 * 但"同一份状态有两个对象"迟早会在某次改动里分叉。
 */
@Component
public class LocalIndexStore {

    private final IndexStore store;
    private final String role;
    private final String shardNum;

    public LocalIndexStore(@Value("${hawk.cloud.recall.index-dir:cloud/recall}") String indexDirRoot,
                           @Value("${hawk.cloud.recall.role}") String role,
                           @Value("${hawk.cloud.recall.shard-num}") String shardNum) {
        this.role = role;
        this.shardNum = shardNum;
        this.store = new IndexStore(Path.of(indexDirRoot, role, shardNum));
    }

    public IndexStore store() {
        return store;
    }

    /** 本节点的角色，形如 {@code vector-recall}。收索引时用它校验请求里的 role。 */
    public String role() {
        return role;
    }

    /** 本节点的分片名，形如 {@code shard-0}。 */
    public String shardNum() {
        return shardNum;
    }
}

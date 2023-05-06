package hawk.recall.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import util.DateUtil;

@Service
public class IndexService {

    @Value("base.path")
    private String basePath;

    @Autowired
    Environment env;

    public boolean loadIndex(){
        String dateStr = DateUtil.getDateStr();
        String shard = env.getProperty("spring.cloud.nacos.discovery.metadata.shard");
        String indexPath = basePath.concat("/").concat(dateStr).concat("/").concat(shard);
        return true;
    }
}

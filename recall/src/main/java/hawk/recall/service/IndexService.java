package hawk.recall.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.scp.client.ScpClient;
import org.apache.sshd.scp.client.ScpClientCreator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import util.DateUtil;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

@Service
@Slf4j
public class IndexService {

    private String ip = InetAddress.getLocalHost().getHostAddress();

    @Value("server.port")
    private String port;

    @Value("base.path")
    private String basePath;

    @Value("ssh.password")
    private String sshPassword;

    @Autowired
    Environment env;

    WebClient webClient = WebClient.create();

    public IndexService() throws UnknownHostException {
    }

    public String pullIndex(String indexerHost, int indexerPort){
        long start = System.currentTimeMillis();
        String url = "http://".concat(indexerHost).concat(":").concat(Integer.toString(indexerPort)).
                concat("/cloud-control//get-index-path");
        String tagetPath = webClient.get().uri(url).retrieve().bodyToMono(String.class).
                timeout(Duration.ofSeconds(3L)).block();
        Path indexPath = Paths.get(getIndexPath());
        if(Files.exists(indexPath)){
            String logInfo = "recallHost: " + ip + ", recallPort: " + port + "already has index";
            return logInfo;
        }
        scpDownload(tagetPath, indexPath, indexerHost, "root");
        long end = System.currentTimeMillis();
        String logInfo = "recallHost: " + ip + ", recallPort: " + port + "indxer host: " + indexerHost +
                ", indexer port： " + indexerPort + ", pull index takes " + (end - start) + " milliseconds";
        log.info(logInfo);

        return logInfo;
    }

    public String getIndexPath(){
        String applicationName = env.getProperty("spring.application.name");
        String dateStr = DateUtil.getDateStr();
        String shard = env.getProperty("spring.cloud.nacos.discovery.metadata.shard");
        String indexPath = basePath.concat("/").concat(applicationName).concat("/").concat(dateStr).concat("/").concat(shard);
        return indexPath;
    }

    public void scpDownload(String targetPath, Path localPath, String remoteHost, String username){
        SshClient client = SshClient.setUpDefaultClient();
        client.start();
        try {
            ClientSession session = client.connect(username, remoteHost, 22).verify().getSession();
            session.addPasswordIdentity(sshPassword);
            boolean isSuccess = session.auth().verify().isSuccess();
            if(isSuccess){
                ScpClientCreator scpClientCreator = ScpClientCreator.instance();
                ScpClient scpClient = scpClientCreator.createScpClient(session);
                scpClient.download(targetPath, localPath, ScpClient.Option.Recursive);
                if (scpClient != null) {
                    scpClient = null;
                }
                if (session != null && session.isOpen()) {
                    session.close();
                }
                if (client != null && client.isOpen()) {
                    client.stop();
                    client.close();
                }
            }
        } catch (IOException e) {
            log.error("met IOException while doing scp");
        }
    }
}

package hawk.recall.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.net.UnknownHostException;

import static java.net.InetAddress.getLocalHost;

@Configuration
@Data
public class HostInfo {

    @Value("${server.port}")
    private String port;

    private String ip = getLocalHost().getHostAddress();

    public HostInfo() throws UnknownHostException {
    }
}

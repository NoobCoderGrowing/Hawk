package hawk.shard.service;

import document.Document;
import field.DoubleField;
import field.Field;
import field.StringField;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.scheduler.Schedulers;

import javax.annotation.Resource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

@Service
@Slf4j
public class CloudIndexing {

    @Value("${shard.count}")
    int shardCount;

    @Value("${shard.limit}")
    int shardLimit;

    @Resource
    ShardMapWatcher shardMapWatcher;

    WebClient webClient = WebClient.create();

    // magic number from "The Hitchhiker’s Guide to the Galaxy"
    Random random = new Random(42);

    public void refreshIndexWriter(ConcurrentHashMap<String, List<ServiceInstance>> indexerMap){
        List<CompletableFuture<String>> refreshWriterResult = new ArrayList<>();
        sendRefreshIndexerWriterRequest(indexerMap, refreshWriterResult);
        batchBlocking(refreshWriterResult, "refresh index writer");
    }

    public void cloudAddDocFromFileData(ConcurrentHashMap<String, List<ServiceInstance>> indexerMap){
        List<CompletableFuture<String>> memIndexingResult = new ArrayList<>();
        List<List<Document>> documentSharding = createDocSharding();
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream("goods-short.csv");
        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
        String line;
        int count = 0;
        try {
            while ((line = bufferedReader.readLine()) != null){
                String[] strings = line.split("\t");
                int id = Integer.parseInt(strings[0]);
                String title = strings[1];
                float price = Float.parseFloat(strings[2]);
                double doublePrice = (double) price;
                Document document = new Document();
                StringField stringField = new StringField("title", title, Field.Tokenized.YES, Field.Stored.YES);
                DoubleField doubleField = new DoubleField("price", doublePrice, Field.Tokenized.YES, Field.Stored.YES);
                document.add(stringField);
                document.add(doubleField);
                int shardNum = id % shardCount;
                documentSharding.get(shardNum).add(document);
                count++;
                if(count >= shardCount * shardLimit){
                    sendIndexingRequest(documentSharding, indexerMap, memIndexingResult);
                    documentSharding = createDocSharding();
                    count = 0;
                }
            }
            //last cloud indexing
            if(documentSharding.get(0).size() >0 ) sendIndexingRequest(documentSharding, indexerMap, memIndexingResult);
        }catch (IOException e){
            log.error("fail to read goods in cloudIndexing");
            System.exit(1);
        }
        batchBlocking(memIndexingResult, "indexing task");
    }

    public void cloudCommit(ConcurrentHashMap<String, List<ServiceInstance>> indexerMap){
        List<CompletableFuture<String>> commitResult = new ArrayList<>();
        sendCommitRequest(indexerMap, commitResult);
        batchBlocking(commitResult, "commit task");
    }

    public void cloudPullIndex(ConcurrentHashMap<String, List<ServiceInstance>> recallMap,
                               ConcurrentHashMap<String, List<ServiceInstance>> indexerMap){
        List<CompletableFuture<String>> pullResult = new ArrayList<>();
        for (Map.Entry<String, List<ServiceInstance>> entry : recallMap.entrySet()) {
            String shardName = entry.getKey();
            List<ServiceInstance> recallInstances = entry.getValue();
            List<ServiceInstance> indexerInstances = indexerMap.get(shardName);
            for (int i = 0; i < recallInstances.size(); i++) {
                int indexerIndex = random.nextInt(indexerInstances.size());
                ServiceInstance indexer = recallInstances.get(indexerIndex);
                String indexerHost = indexer.getHost();
                int indexerPort = indexer.getPort();
                String recallHost = recallInstances.get(i).getHost();
                int recallPort = recallInstances.get(i).getPort();
                String url = "http://".concat(recallHost).concat(":").concat(Integer.toString(recallPort)).
                        concat("/cloud-control/pullIndex/").concat(indexerHost).concat("/").
                        concat(Integer.toString(indexerPort));
                CompletableFuture<String> ret = webClient.get().uri(url).retrieve().bodyToMono(String.class).
                        subscribeOn(Schedulers.single()).toFuture();
                pullResult.add(ret);
            }
        }
        batchBlocking(pullResult, "pull index task");
    }

    public void cloudUploadIndex(ConcurrentHashMap<String, List<ServiceInstance>> recallMap,
                               ConcurrentHashMap<String, List<ServiceInstance>> indexerMap){
        List<CompletableFuture<String>> pullResult = new ArrayList<>();
        for (Map.Entry<String, List<ServiceInstance>> entry : recallMap.entrySet()) {
            String shardName = entry.getKey();
            List<ServiceInstance> recallInstances = entry.getValue();
            List<ServiceInstance> indexerInstances = indexerMap.get(shardName);
            for (int i = 0; i < recallInstances.size(); i++) {
                int indexerIndex = random.nextInt(indexerInstances.size());
                ServiceInstance indexer = indexerInstances.get(indexerIndex);
                String indexerHost = indexer.getHost();
                int indexerPort = indexer.getPort();
                String recallHost = recallInstances.get(i).getHost();
                int recallPort = recallInstances.get(i).getPort();
                String url = "http://".concat(indexerHost).concat(":").concat(Integer.toString(indexerPort)).
                        concat("/cloud-control/upload-index/").concat(recallHost).concat("/").
                        concat(Integer.toString(recallPort));
                CompletableFuture<String> ret = webClient.get().uri(url).retrieve().bodyToMono(String.class).
                        subscribeOn(Schedulers.single()).toFuture();
                pullResult.add(ret);
            }
        }
        batchBlocking(pullResult, "upload index task");
    }

    public void cloudSwitchSearchEngine(ConcurrentHashMap<String, List<ServiceInstance>> recallMap){
        List<CompletableFuture<String>> switchEngineResult = new ArrayList<>();
        for (Map.Entry<String, List<ServiceInstance>> entry : recallMap.entrySet()) {
            List<ServiceInstance> recallInstances = entry.getValue();
            for (int i = 0; i < recallInstances.size(); i++) {
                String recallHost = recallInstances.get(i).getHost();
                int recallPort = recallInstances.get(i).getPort();
                String url = "http://".concat(recallHost).concat(":").concat(Integer.toString(recallPort)).
                        concat("/cloud-control/hot-switch-search-engine");
                CompletableFuture<String> ret = webClient.get().uri(url).retrieve().bodyToMono(String.class).
                        subscribeOn(Schedulers.single()).toFuture();
                switchEngineResult.add(ret);
            }
        }
        batchBlocking(switchEngineResult, "switcher searcher task");
    }

    public void batchBlocking(List<CompletableFuture<String>> result, String taskName){
        for (int i = 0; i < result.size(); i++) {
            try {
                log.info(taskName + " ===>>> " + result.get(i).get());;
            } catch (InterruptedException e) {
                log.info("receive InterruptedException during cloud indexing " + taskName);
            } catch (ExecutionException e) {
                log.info("receive ExecutionException during cloud indexing" + taskName);
            }
        }
    }

    public List<List<Document>> createDocSharding(){
        List<List<Document>> documentSharding = new ArrayList<>();
        for (int i = 0; i < shardCount; i++) {
            List<Document> docShard = new ArrayList<>();
            documentSharding.add(docShard);
        }
        return documentSharding;
    }

    public void sendRefreshIndexerWriterRequest(ConcurrentHashMap<String, List<ServiceInstance>> indexerMap,
                                                List<CompletableFuture<String>> result){
        for (Map.Entry<String, List<ServiceInstance>> entry : indexerMap.entrySet()) {
            List<ServiceInstance> instances = entry.getValue();
            for (int i = 0; i < instances.size(); i++) {
                String host = instances.get(i).getHost();
                int port = instances.get(i).getPort();
                String url = "http://".concat(host).concat(":").concat(Integer.toString(port)).
                        concat("/cloud-control/refresh-index-writer");
                CompletableFuture<String> ret = webClient.get().uri(url).retrieve().bodyToMono(String.class).
                        subscribeOn(Schedulers.single()).toFuture();
                result.add(ret);
            }
        }
    }

    public void sendCommitRequest(ConcurrentHashMap<String, List<ServiceInstance>> indexerMap,
                                  List<CompletableFuture<String>> commitResult){
        for (Map.Entry<String, List<ServiceInstance>> entry : indexerMap.entrySet()) {
            List<ServiceInstance> instances = entry.getValue();
            for (int i = 0; i < instances.size(); i++) {
                String host = instances.get(i).getHost();
                int port = instances.get(i).getPort();
                String url = "http://".concat(host).concat(":").concat(Integer.toString(port)).
                        concat("/cloud-control/commit");
                CompletableFuture<String> ret = webClient.get().uri(url).retrieve().bodyToMono(String.class).
                        subscribeOn(Schedulers.single()).toFuture();
                commitResult.add(ret);
            }
        }
    }

    public void sendIndexingRequest(List<List<Document>> documentShardings, Map<String, List<ServiceInstance>>
            indexerMap, List<CompletableFuture<String>> result){
        for (int i = 0; i < documentShardings.size(); i++) {
            List<Document> docShard = documentShardings.get(i);
            String shardName = Integer.toString(i+1);
            List<ServiceInstance> shardInstances  = indexerMap.get(shardName);
            for (int j = 0; j < shardInstances.size(); j++) {
                String host = shardInstances.get(j).getHost();
                int port = shardInstances.get(j).getPort();
                String url = "http://".concat(host).concat(":").concat(Integer.toString(port)).
                        concat("/cloud-control/index-documents");
                CompletableFuture<String> ret = webClient.post().uri(url).contentType(MediaType.APPLICATION_JSON).
                        bodyValue(docShard).retrieve().bodyToMono(String.class).timeout(Duration.ofSeconds(3)).
                        subscribeOn(Schedulers.single()).toFuture();
                result.add(ret);
            }
        }
    }
}

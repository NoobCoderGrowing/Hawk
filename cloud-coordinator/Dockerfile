# cloud-coordinator 的镜像。
#
# 构建前先在宿主机打好包（镜像只是把胖 jar 拷进去，不在容器里编译）：
#     JAVA_HOME=~/jdk-25 mvn -DskipTests package
#
# 构建上下文是**本模块目录**，不是仓库根。仓库根里有 model.tar.gz（149MB）、model/、
# index-data/、goods.csv —— 拿它当上下文每次都要白传几百 MB 给 daemon。所以每个模块
# 各有一份 Dockerfile，各自以模块目录为上下文。
FROM eclipse-temurin:25-jre

WORKDIR /app

# 用通配符而不是写死 1.0-SNAPSHOT：版本号变了不用改这里。
# target 下还有一个 *.jar.original（repackage 前的瘦 jar），它不匹配 *.jar，不会被选中。
COPY target/cloud-coordinator-*.jar /app/app.jar

# 与 hawk-cloud-coordinator.yml 的 server.port 一致。
EXPOSE 8090

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

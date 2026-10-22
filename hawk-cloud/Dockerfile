# 分布式层的镜像 —— **一个镜像跑四种角色**，由 HAWK_ROLE 决定装配哪一套：
#     text-indexer / vector-indexer / text-recall / vector-recall / coordinator / mcp
#
# 构建前先在宿主机打好包（镜像只是把胖 jar 拷进去，不在容器里编译）：
#     JAVA_HOME=~/jdk-25 mvn -DskipTests package
#
# 构建上下文是**本模块目录**，不是仓库根。仓库根里有 model.tar.gz（149MB）、model/、
# index-data/、goods.csv —— 拿它当上下文每次都要白传几百 MB 给 daemon。
FROM eclipse-temurin:25-jre

WORKDIR /app

# 用通配符而不是写死 1.0-SNAPSHOT：版本号变了不用改这里。
# target 下还有一个 *.jar.original（repackage 前的瘦 jar），它不匹配 *.jar，不会被选中。
COPY target/hawk-cloud-*.jar /app/app.jar

# 各角色历史上各用一个端口，这里全声明（仅元数据；实际端口由 HAWK_PORT 决定）。
# 8093 留给 mcp 角色。
EXPOSE 8090 8091 8092 8093

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

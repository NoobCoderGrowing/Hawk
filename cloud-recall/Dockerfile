# cloud-recall 的镜像。
#
# 构建前先在宿主机打好包（镜像只是把胖 jar 拷进去，不在容器里编译）：
#     JAVA_HOME=~/jdk-25 mvn -DskipTests package
#
# 构建上下文是**本模块目录**，不是仓库根 —— 理由见 cloud-coordinator/Dockerfile 的注释。
FROM eclipse-temurin:25-jre

WORKDIR /app

COPY target/cloud-recall-*.jar /app/app.jar

# 与 hawk-cloud-recall.yml 的 server.port 一致。
EXPOSE 8092

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

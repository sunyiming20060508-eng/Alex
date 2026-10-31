# 后端镜像：两阶段。构建阶段要 Maven + JDK，运行阶段只要 JRE——
# 把工具链留在构建层，运行层比直接打一份 fat jar 上 ubuntu 小一大截，
# 而且运行时镜像里没有 mvn，被进去也拿不到构建能力。
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src

# 先只拷 pom 拉依赖：源码改动不会让这一层缓存失效，本地反复构建时差别很大
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src
# 测试在 CI 里跑，构建镜像这一步不重复跑（跑一次全量单测要几十秒，且镜像内容不该依赖测试是否通过）
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app

# 不用 root 跑：容器逃逸或误挂宿主目录时，root 进程的破坏面大得多
RUN useradd --system --uid 10001 --create-home appuser
COPY --from=build /src/target/life-service-api-0.1.0.jar app.jar
RUN mkdir -p /app/logs /app/uploads && chown -R appuser:appuser /app
USER appuser

# 日志和上传目录挂出来，容器重建不丢（logback 写 ./logs，见 logback-spring.xml）
VOLUME ["/app/logs", "/app/uploads"]
EXPOSE 8080

# JAVA_OPTS 留成可调开关（内存、时区），但**必须用 exec 起 java**：
# 否则 java 不是 PID 1，docker stop 的 SIGTERM 会被 sh 吃掉，
# application.yml 里的 server.shutdown: graceful 就形同虚设——在途请求直接被切断。
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

# 这个镜像里没有 curl/wget，所以用 bash 的 /dev/tcp 探端口。
# 它只证明"进程在听"，不等价于依赖健康——真正的 db/redis 探活看 /actuator/health，
# 编排层要那个级别的话应该引一个带 curl 的运行时镜像，这里不为探测把镜像撑大。
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080' || exit 1

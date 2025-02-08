FROM eclipse-temurin:17-jre
COPY target/dependencies/ ./
RUN true
COPY target/snapshot-dependencies/ ./
RUN true
COPY target/spring-boot-loader/ ./
RUN true
COPY target/application/ ./
RUN true
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS "-XX:MaxRAM=256m -Xss256k -XX:ActiveProcessorCount=1"
ENTRYPOINT ["java", "org.springframework.boot.loader.JarLauncher"]
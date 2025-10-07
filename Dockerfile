FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/smart_home.jar smart_home.jar
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAM=256m -Xss256k -XX:ActiveProcessorCount=1"
ENTRYPOINT ["java", "-jar", "smart_home.jar", "--spring.config.additional-location=/app/config/"]

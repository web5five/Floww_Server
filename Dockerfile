FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --chown=10001:10001 target/floww-server-0.1.0.jar /app/server.jar
ENV JAVA_TOOL_OPTIONS="-Xmx512m"
ENV FLOWW_BIND_ADDRESS="0.0.0.0"
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/server.jar"]

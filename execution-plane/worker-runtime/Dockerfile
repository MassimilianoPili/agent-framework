FROM eclipse-temurin:21-jre-jammy

LABEL maintainer="Agent Framework"
LABEL description="Worker-runtime: standalone JVM for heavy workers (Phase 2 #29)"

WORKDIR /app

COPY target/worker-runtime-*.jar app.jar

ENV WORKER_TYPE=unknown
ENV ORCHESTRATOR_URL=http://orchestrator:8080
ENV SERVER_PORT=8100

EXPOSE 8100

ENTRYPOINT ["java", "-jar", "app.jar"]

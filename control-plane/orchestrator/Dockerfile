FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="agent-framework"
LABEL description="Agent Framework Orchestrator"

WORKDIR /app

COPY target/orchestrator-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]

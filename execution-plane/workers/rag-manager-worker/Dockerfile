FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="agent-framework"
LABEL description="RAG Manager Worker"

WORKDIR /app

COPY target/rag-manager-worker-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]

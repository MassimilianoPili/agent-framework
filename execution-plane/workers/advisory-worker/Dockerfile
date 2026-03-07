FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="agent-framework"
LABEL description="Advisory Worker (Council System)"

WORKDIR /app

COPY target/advisory-worker-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]

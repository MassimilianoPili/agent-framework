FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/compensator-manager-worker-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

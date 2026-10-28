FROM eclipse-temurin:25-jre
# Built by `sandbox/gradlew -p sandbox assemble` (or :services:inventory-service:bootJar).
COPY build/libs/inventory-service.jar /app/inventory-service.jar
EXPOSE 8081
# The OpenTelemetry agent and the stack extension are attached from outside via JAVA_TOOL_OPTIONS (docker-compose.yml).
ENTRYPOINT ["java", "-jar", "/app/inventory-service.jar"]

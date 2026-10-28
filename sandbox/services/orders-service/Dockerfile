FROM eclipse-temurin:25-jre
# Built by `sandbox/gradlew -p sandbox assemble` (or :services:orders-service:bootJar).
COPY build/libs/orders-service.jar /app/orders-service.jar
EXPOSE 8080
# The OpenTelemetry agent and the stack extension are attached from outside via JAVA_TOOL_OPTIONS (docker-compose.yml).
ENTRYPOINT ["java", "-jar", "/app/orders-service.jar"]

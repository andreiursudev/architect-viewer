FROM eclipse-temurin:25-jre
# Built by `sandbox/gradlew -p sandbox assemble` (or :services:shipping-service:bootJar).
COPY build/libs/shipping-service.jar /app/shipping-service.jar
EXPOSE 8082
# The OpenTelemetry agent and the stack extension are attached from outside via JAVA_TOOL_OPTIONS (docker-compose.yml).
ENTRYPOINT ["java", "-jar", "/app/shipping-service.jar"]

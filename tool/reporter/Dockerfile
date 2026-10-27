FROM eclipse-temurin:25-jre
COPY build/libs/reporter.jar /app/reporter.jar
ENV STACK_REPORTER_RECORDINGS_DIR=/recordings
RUN mkdir -p /recordings
EXPOSE 4318
ENTRYPOINT ["java", "-jar", "/app/reporter.jar"]

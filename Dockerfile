# AgentGate (API + console) as one image: the React console is built first and bundled into the Spring Boot jar.
# Used by deploy/selfhost/compose.yaml; build from the repository root: docker build -t agentgate .

FROM node:26-alpine AS frontend
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
# The type check also covers tests, which load the runtime's example workflows.
COPY runtime/examples /runtime/examples
RUN npm run build

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon -q dependencies > /dev/null
COPY src src
COPY --from=frontend /frontend/dist src/main/resources/static
RUN ./gradlew --no-daemon -q bootJar -x test && cp build/libs/agentgate-*-SNAPSHOT.jar /agentgate.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 agentgate
COPY --from=build /agentgate.jar /app/agentgate.jar
USER agentgate
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/agentgate.jar"]

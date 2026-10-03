# The console is built in its own stage, so npm's layer is cached independently of the
# Java build and the final image never contains Node.
FROM node:22-alpine AS ui
WORKDIR /ui
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
COPY core/pom.xml core/pom.xml
COPY sdk/pom.xml sdk/pom.xml
COPY server/pom.xml server/pom.xml
# Warms the dependency cache on its own layer, so a source-only change does not resolve
# everything again. Best effort: whatever it misses is fetched by the build below.
RUN mvn -B -q dependency:go-offline || true
COPY core/ core/
COPY sdk/ sdk/
COPY server/ server/
COPY --from=ui /ui/dist frontend/dist
# Tests need a Docker daemon for Testcontainers, which an image build does not have.
# They run in CI and locally instead; see .github/workflows/ci.yml.
RUN mvn -B -DskipTests -pl server -am package

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
RUN addgroup -S flags && adduser -S flags -G flags
COPY --from=build /src/server/target/flags-server-*.jar app.jar
USER flags
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]

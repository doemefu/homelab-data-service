# Base images are pinned by multi-arch index digest (linux/amd64 + linux/arm64); Dependabot's
# docker ecosystem bumps tag and digest together.

# Build stage
FROM eclipse-temurin:25-jdk-alpine@sha256:425f0b733cb23843bd78dbe5552513976d0cf12b9722752c48a5161cf5cd2f32 AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline -q
COPY src/ src/
RUN ./mvnw clean package -DskipTests -q

# Runtime stage
FROM eclipse-temurin:25-jre-alpine@sha256:56f273059de6b13a6b49fe0ded691ed2d0399bbee76156a8f2d385f7524a3d43
WORKDIR /app

# Non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=build /app/target/data-service-*.jar app.jar

EXPOSE 8082

ENTRYPOINT ["java", "-jar", "app.jar"]

FROM node:22-alpine AS frontend-build
WORKDIR /workspace/front
COPY front/package*.json ./
RUN npm ci
COPY front/ ./
RUN npm run build

FROM eclipse-temurin:17-jdk-alpine AS backend-build
WORKDIR /workspace/back
COPY back/ ./
COPY --from=frontend-build /workspace/front/dist/ src/main/resources/static/
RUN chmod +x mvnw && ./mvnw -B -DskipTests clean package

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S medeat && adduser -S medeat -G medeat \
    && mkdir -p /data/uploads \
    && chown -R medeat:medeat /app /data
COPY --from=backend-build --chown=medeat:medeat /workspace/back/target/*.jar app.jar
USER medeat
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

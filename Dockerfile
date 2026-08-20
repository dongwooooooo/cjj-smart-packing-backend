FROM eclipse-temurin:25.0.3_9-jdk AS build
WORKDIR /app

# 의존성 계층을 먼저 만들어 캐시를 살린다. 소스만 바뀌면 이 단계는 재실행되지 않는다.
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

COPY src ./src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:25.0.3_9-jre
WORKDIR /app
COPY --from=build /app/build/libs/backend-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8000
ENTRYPOINT ["java", "-jar", "app.jar"]

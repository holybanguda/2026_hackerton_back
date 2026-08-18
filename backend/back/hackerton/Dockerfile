# 1단계: 서버 내부에서 빌드
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build

COPY . .

RUN chmod +x gradlew
RUN ./gradlew bootJar -x test

# 2단계: 실행
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=builder /build/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
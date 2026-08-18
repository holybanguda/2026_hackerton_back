# 1단계: 서버 내부에서 빌드 수행
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build

COPY . .

RUN chmod +x gradlew
RUN ./gradlew bootJar -x test

# 2단계: 깔끔한 실행 환경
FROM eclipse-temurin:21-jre
WORKDIR /app

# 빌드된 jar 파일을 절대 경로로 정확히 복사
COPY --from=builder /build/build/libs/*.jar /app/app.jar

EXPOSE 8080

# 상대 경로가 아닌 절대 경로로 실행 명령 지정
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
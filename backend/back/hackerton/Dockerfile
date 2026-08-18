# 1단계: 빌드 환경
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build

# 깃허브 전체 최상단 소스코드를 통째로 복사합니다.
COPY . .

# 깃허브 최상단 기준의 실제 경로로 권한 부여
RUN chmod +x backend/back/hackerton/gradlew

# 빌드 작업을 수행할 폴더로 이동
WORKDIR /build/backend/back/hackerton

# 빌드 실행
RUN ./gradlew bootJar -x test

# 2단계: 실행 환경
FROM eclipse-temurin:21-jre
WORKDIR /app

# 빌드된 jar 파일 복사
COPY --from=builder /build/backend/back/hackerton/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
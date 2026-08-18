# 1단계: 서버(도커) 내부에서 직접 빌드 수행
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build

# 현재 디렉토리의 모든 소스를 복사
COPY . .

# 실행 권한 부여 및 빌드 실행
RUN chmod +x gradlew
RUN ./gradlew bootJar -x test

# 2단계: 가벼운 실행 환경만 구축
FROM eclipse-temurin:21-jre
WORKDIR /app

# 빌드된 jar 파일만 쏙 가져오기
COPY --from=builder /build/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
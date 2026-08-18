# 1단계: 빌드 환경
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build

# 현재 hackerton 폴더 안의 모든 소스코드를 복사합니다.
COPY . .

# 권한 부여 및 빌드 실행
RUN chmod +x gradlew
RUN ./gradlew bootJar -x test

# 2단계: 실행 환경
FROM eclipse-temurin:21-jre
WORKDIR /app

# 빌드된 jar 파일 가져오기
COPY --from=builder /build/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
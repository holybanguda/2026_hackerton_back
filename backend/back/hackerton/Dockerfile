# 1단계: 서버 내부에서 빌드 수행
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build

# 현재 폴더의 모든 소스 복사
COPY . .

# 권한 부여 및 빌드
RUN chmod +x gradlew
RUN ./gradlew bootJar -x test

# 2단계: 깔끔한 실행 환경
FROM eclipse-temurin:21-jre
WORKDIR /app

# 빌드된 jar 파일만 가져오기
COPY --from=builder /build/build/libs/*.jar app.jar

# 8080 포트 개방
EXPOSE 8080

# 실행 명령어 (절대 엉뚱한 gradlew를 찾지 않고 jar만 실행합니다)
ENTRYPOINT ["java", "-jar", "app.jar"]
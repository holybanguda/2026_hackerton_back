# 1단계: 빌드 환경 (전체 프로젝트 소스 복사)
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build

# 깃허브 최상단 기준의 전체 경로를 다 복사해옵니다
COPY . .

# backend/back/hackerton 폴더 안으로 들어가서 빌드 실행
WORKDIR /build/backend/back/hackerton
RUN chmod +x gradlew
RUN ./gradlew bootJar -x test

# 2단계: 실행 환경
FROM eclipse-temurin:21-jre
WORKDIR /app

# 방금 빌드된 jar 파일만 쏙 가져오기
COPY --from=builder /build/backend/back/hackerton/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
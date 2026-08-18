# 1단계: 빌드 환경
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build

# 깃허브 전체 내용을 복사합니다.
COPY . .

# 여기서 중요한 점: gradlew 파일이 실제로 어디에 있는지 명시적으로 절대 경로로 chmod를 실행합니다.
# 본인의 프로젝트 구조에 맞춰 backend/back/hackerton/gradlew 경로가 맞는지 꼭 확인하세요!
RUN chmod +x backend/back/hackerton/gradlew

# 빌드 실행 (해당 폴더로 이동해서 실행)
WORKDIR /build/backend/back/hackerton
RUN ./gradlew bootJar -x test

# 2단계: 실행 환경
FROM eclipse-temurin:21-jre
WORKDIR /app

# 빌드 결과물(jar)을 가져옵니다.
COPY --from=builder /build/backend/back/hackerton/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
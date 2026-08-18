FROM eclipse-temurin:21-jre
WORKDIR /app

# 루트 디렉토리가 hackerton으로 잡혀있으므로, build/libs 폴더 안의 jar를 바로 복사합니다.
COPY build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
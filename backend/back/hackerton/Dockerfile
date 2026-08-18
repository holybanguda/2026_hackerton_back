
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build
COPY . .
RUN chmod +x gradlew
RUN ./gradlew bootJar -x test


FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /build/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
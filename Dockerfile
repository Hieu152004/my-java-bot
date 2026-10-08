# Stage 1: Build bằng Maven để tạo file jar có chứa toàn bộ dependencies
FROM maven:3.8.8-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Chạy ứng dụng từ file jar đã đóng gói
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/telegram-casino-bot-1.0-SNAPSHOT-jar-with-dependencies.jar app.jar

EXPOSE 8080
CMD ["java", "-jar", "app.jar"]

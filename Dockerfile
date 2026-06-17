FROM eclipse-temurin:21-jdk

WORKDIR /app

COPY . .

RUN chmod +x mvnw
RUN ./mvnw clean package -DskipTests

CMD ["java","-jar","auth-service/target/auth-service-0.0.1-SNAPSHOT.jar"]
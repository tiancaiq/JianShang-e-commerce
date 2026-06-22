FROM eclipse-temurin:21-jdk

WORKDIR /app

COPY . .

RUN chmod +x mvnw
RUN ./mvnw clean package -DskipTests -pl api-gateway -am

CMD ["java","-jar","api-gateway/target/api-gateway-0.0.1-SNAPSHOT.jar"]

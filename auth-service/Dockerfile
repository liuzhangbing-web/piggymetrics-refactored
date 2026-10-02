FROM eclipse-temurin:17-jre
LABEL maintainer="dabing <zhangbing.liu@outlook.com>"

ADD ./target/auth-service.jar /app/
CMD ["java", "-Xmx200m", "-jar", "/app/auth-service.jar"]

EXPOSE 5000

FROM eclipse-temurin:17-jre
LABEL maintainer="dabing <zhangbing.liu@outlook.com>"

ADD ./target/notification-service.jar /app/
CMD ["java", "-Xmx200m", "-jar", "/app/notification-service.jar"]

EXPOSE 8000

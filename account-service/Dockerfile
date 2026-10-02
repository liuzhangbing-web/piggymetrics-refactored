FROM eclipse-temurin:17-jre
LABEL maintainer="dabing <zhangbing.liu@outlook.com>"

ADD ./target/account-service.jar /app/
CMD ["java", "-Xmx200m", "-jar", "/app/account-service.jar"]

EXPOSE 6000

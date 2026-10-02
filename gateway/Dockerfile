FROM eclipse-temurin:17-jre
LABEL maintainer="dabing <zhangbing.liu@outlook.com>"

ADD ./target/gateway.jar /app/
CMD ["java", "-Xmx200m", "-jar", "/app/gateway.jar"]

EXPOSE 4000

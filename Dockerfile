# syntax=docker/dockerfile:1
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY backend ./backend
COPY lib ./lib
RUN find backend -name '*.java' -print > sources.txt \
    && javac --release 17 -cp 'lib/*' -d out @sources.txt
ADD --chmod=644 --checksum=sha256:0353648eaa1c91e0f4020c959abf756bc866ffd583df22ae6b6f6e0cbd43eb44 https://repo.maven.apache.org/maven2/com/mysql/mysql-connector-j/9.7.0/mysql-connector-j-9.7.0.jar lib/mysql-connector-j.jar

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/out ./out
COPY --from=build /app/lib ./lib
COPY frontend ./frontend
ENV SOFTNOTE_BIND_HOST=0.0.0.0
USER 10001:10001
ENTRYPOINT ["java", "-cp", "out:lib/*", "backend.api.ApiServer"]

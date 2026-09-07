# ── Etapa 1: build ────────────────────────────────────────────────────────────
# El build corre acá adentro y no en la máquina de nadie: la imagen que se despliega
# sale siempre del mismo JDK y el mismo Maven, sin depender de lo que cada casa tenga
# instalado. Esta línea es la declaración de versión del proyecto.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# El pom primero y solo: mientras no cambien las dependencias, esta capa queda cacheada
# y un cambio de código no vuelve a bajar medio Maven Central.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q package -DskipTests

# ── Etapa 2: runtime ──────────────────────────────────────────────────────────
# JRE y no JDK: la imagen final no necesita compilar nada. Se lleva solo el .jar.
FROM eclipse-temurin:17-jre

# Usuario no-root (CONTRATO.md §6): si alguien se escapa del proceso, no cae en un root.
RUN useradd --create-home --uid 10001 app
WORKDIR /app

COPY --from=build /build/target/app-java.jar /app/app-java.jar

# La bitácora se monta desde la casa; el directorio tiene que existir y ser del usuario
# no-root, o el primer write falla por permisos.
RUN mkdir -p /app/logs && chown -R app:app /app
USER app

ENV PORT=8080 TP_LOGS=/app/logs
EXPOSE 8080

# Contra grpc.health.v1.Health, no con curl: no hay HTTP que consultar.
# start-period generoso: la JVM tarda en arrancar y sin eso el contenedor se declara
# unhealthy antes de haber terminado de levantar.
HEALTHCHECK --interval=10s --timeout=5s --start-period=20s --retries=3 \
  CMD ["java", "-XX:TieredStopAtLevel=1", "-Xmx64m", "-cp", "/app/app-java.jar", \
       "ar.edu.unlu.sdypp.Healthcheck", "localhost:8080"]

# Forma exec y no shell: así el PID 1 es la JVM y recibe el SIGTERM de `docker stop`
# directamente. Con la forma shell el PID 1 sería /bin/sh, la señal no llegaría a Java
# y el graceful shutdown no correría nunca — moriría de golpe a los 10s por SIGKILL.
ENTRYPOINT ["java", "-jar", "/app/app-java.jar"]

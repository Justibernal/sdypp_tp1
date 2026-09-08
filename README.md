# SDyPP — App Java replicada

Servicio **gRPC** en Java, replicado entre las casas del equipo y desplegado en
contenedores. Réplicas *stateless* detrás del balanceador de Plataforma, estado compartido
en Redis y despliegues que no cortan el servicio.

El contrato con la App Python está en **[`CONTRATO.md`](CONTRATO.md)** y el esquema formal
en **[`src/main/proto/contrato.proto`](src/main/proto/contrato.proto)** — ante una
diferencia, manda el contrato, no este README.

> La entrega de la Clase 1 (servidor HTTP/JSON sin dependencias y deploy manual) queda en
> el tag `Entrega-Clase1`.

## Integrantes

* **María Agustina Ortiz** — Legajo *(completar)*
* **Justino Bernal** — Legajo *(completar)*
* **Sebastián …** — Legajo *(completar)*

---

## Los RPC

| RPC | Qué hace |
| :--- | :--- |
| `Identidad` | app, lenguaje, equipo, versión, host y arranque |
| `Salud` | chequeo de salud (enum `SANO`, no string) |
| `Echo` | recibe `ping`, responde `pong` |
| `ListarPersonas` | lo guardado, ordenado por `id` |
| `CrearPersona` | alta; el `id` **lo asigna la base** |

Además se expone **`grpc.health.v1.Health`** (el estándar, que consultan el `HEALTHCHECK`
del contenedor y el balanceador) y **reflection**, que permite al verificador del equipo
cruzado probarnos sin tener el `.proto`.

---

## Qué usa el proyecto y por qué

| Pieza | Para qué | Por qué esta |
| :--- | :--- | :--- |
| **Maven** (`./mvnw`) | Build y dependencias | Gradle no estaba instalado. El wrapper pinnea Maven 3.9.11 en el repo: no depende de lo que cada uno tenga. |
| **`protobuf-maven-plugin`** | Compila el `.proto` a clases Java + stubs gRPC | Baja el `protoc` correcto según SO/arquitectura (`os-maven-plugin`); nadie instala protoc a mano. |
| **`grpc-netty-shaded`** | Transporte HTTP/2 | Trae su propio Netty renombrado adentro: no puede chocar con otra versión de Netty en el classpath. |
| **`grpc-services`** | `HealthStatusManager` + `ProtoReflectionServiceV1` | Son el health estándar y la reflection: dos requisitos del contrato que no queremos escribir a mano. |
| **Jedis** | Cliente de Redis | Sincrónico, encaja con el pool de hilos del server gRPC (un hilo por RPC, bloquea y devuelve). |
| **`maven-shade-plugin`** | Fat jar ejecutable | Un solo `.jar` que se copia y se corre, como el de la Clase 1. Fusiona los `META-INF/services` que gRPC y Netty necesitan para registrar sus providers. |

**Java 17**, declarado en el `pom.xml` (`maven.compiler.release`) y en el `Dockerfile`
(`maven:3.9-eclipse-temurin-17` / `eclipse-temurin:17-jre`). El build oficial corre
**adentro de Docker**: el JDK de la máquina de cada uno no toca el artefacto que se
despliega.

---

## Las clases

| Clase | Responsabilidad |
| :--- | :--- |
| `AppJava` | `main`. Arma el server gRPC, registra los servicios, y el graceful shutdown. |
| `Config` | Todo lo que viene por variable de entorno + las constantes que son contrato. |
| `ServicioImpl` | Los cinco RPC. Validación en el orden que fija el contrato. |
| `RepositorioPersonas` | Redis: esquema de claves y el script Lua del alta atómica. |
| `Bitacora` | Una línea por RPC al disco local, un archivo por réplica. |
| `Cliente` | CLI para probar a mano. Con gRPC no alcanza un `curl`. |
| `Verificador` | El de la demo: dispara N requests, cuenta códigos y reparto. |
| `Healthcheck` | Lo que corre el `HEALTHCHECK` del contenedor. Sale 0 si `SERVING`. |

---

## Levantar el entorno local

```bash
mkdir -p logs/java-1 logs/java-2          # antes: si los crea Docker quedan de root
docker build -t sdypp-app-java:local .
docker network create sdypp

docker run -d --name sdypp-redis --network sdypp -p 6379:6379 \
    redis:8-alpine redis-server --appendonly yes

docker run -d --name sdypp-java-1 --network sdypp -p 8101:8080 \
    -e HOST_NAME=casa-justino-1 -e CASA=casa-justino \
    -e TP_REDIS_URL=redis://sdypp-redis:6379/0 \
    -v "$PWD/logs/java-1:/app/logs" --stop-timeout 15 sdypp-app-java:local

docker run -d --name sdypp-java-2 --network sdypp -p 8102:8080 \
    -e HOST_NAME=casa-justino-2 -e CASA=casa-justino \
    -e TP_REDIS_URL=redis://sdypp-redis:6379/0 \
    -v "$PWD/logs/java-2:/app/logs" --stop-timeout 15 sdypp-app-java:local
```

Probar (con gRPC no alcanza un `curl`):

```bash
./mvnw -q package
java -cp target/app-java.jar ar.edu.unlu.sdypp.Cliente localhost:8101 alta "Ada Lovelace" 100200
java -cp target/app-java.jar ar.edu.unlu.sdypp.Cliente localhost:8102 personas
docker rm -f sdypp-java-1 sdypp-java-2 sdypp-redis
```

Sin Docker: `./mvnw -q package` y
`TP_REDIS_URL=redis://localhost:6379/0 java -jar target/app-java.jar 8080`.

### El deploy blue-green

```bash
./deploy/deploy.sh desplegar   # construye, levanta el color libre AL LADO y conmuta
./deploy/deploy.sh rollback    # vuelve al color anterior, que sigue corriendo
./deploy/deploy.sh estado      # qué color sirve y cómo está cada réplica
./deploy/deploy.sh bajar       # baja todo
```

Probado en los cuatro escenarios que pide el enunciado:

| Escenario | Resultado |
| :--- | :--- |
| Primer deploy, nada sirviendo | `blue` v3 arriba y sirviendo |
| Deploy de una versión nueva | `green` v4 al lado, verify, conmuta; `blue` queda viva |
| Rollback | Vuelve a `blue` v3 en un comando; `green` sigue viva |
| Deploy de una versión rota | `green` nunca llega a `healthy` → **aborta, baja las verdes, no conmuta, sale 1**; `blue` nunca dejó de servir |

El `verify` no se conforma con un `healthy`: **compara la versión** que responde
`Identidad` contra la declarada en `Config.VERSION`. Un ship a medias deja el contenedor
sano corriendo la versión anterior, y eso pasaría un health check sin problema.

La conmutación está aislada en `conmutar_a` / `color_activo`: es el único punto que depende
del equipo Plataforma. Cuando definan la firma del endpoint se cambia ahí y nada más. Sin
`CONMUTADOR_ADMIN` el deploy corre igual y avisa — es el modo de la demo local.

### El verificador

```bash
V="java -cp target/app-java.jar ar.edu.unlu.sdypp.Verificador"
$V carga        localhost:8101 200 8     # N requests: códigos + reparto por instancia
$V concurrencia localhost:8101 999001 25 # 25 altas del mismo legajo a la vez
$V secuencia    localhost:8102 999500    # un alta y la lectura siguiente
```

---

## Variables de entorno

| Variable | Para qué | Default |
| :--- | :--- | :--- |
| `PORT` | Puerto de escucha. El primer argumento de CLI le gana. | `8080` |
| `HOST_NAME` | Identidad de la instancia. Da nombre al archivo de bitácora. | *hostname* |
| `CASA` | Nodo donde corre. Segundo campo de la bitácora. | `casa-desconocida` |
| `TP_REDIS_URL` | Base compartida. **Lleva la contraseña: no se versiona.** | vacío → personas da `UNAVAILABLE` |
| `TP_WORKERS` | Hilos que atienden RPCs a la vez. | `10` |
| `TP_LOGS` | Directorio de la bitácora. | `logs` |

---

## Verificado

| | Qué | Resultado |
| :--- | :--- | :--- |
| ✅ | Los cinco RPC + health estándar + reflection | |
| ✅ | Matriz de verificación de `CONTRATO.md §3` completa | 9/9 casos con el código y el mensaje esperados |
| ✅ | Alta en una réplica, lectura en la otra | El dato está: el estado vive en la base |
| ✅ | 25 altas del mismo legajo simultáneas | 1 `OK` + 24 `ALREADY_EXISTS` → el alta es atómica |
| ✅ | Base caída → `UNAVAILABLE`; el resto de los RPC siguen sanos | |
| ✅ | Base que vuelve → la réplica se recupera **sin reiniciarse** | Ver "Decisiones" |
| ✅ | `SIGTERM` → `NOT_SERVING` → drenado → salida limpia | `docker stop` en 0,5 s |
| ✅ | Contenedor no-root, `HEALTHCHECK` gRPC | `(healthy)` a los 20 s |
| ✅ | `deploy.sh` blue-green con abort y rollback | 4 escenarios probados end-to-end |
| ⬜ | Réplicas repartidas entre las tres casas (Tailscale) | |
| ⬜ | Diagramas de arquitectura por etapa | |

---

## Decisiones

**El formato del timestamp se escribe a mano.** `OffsetDateTime.toString()` y
`DateTimeFormatter.ISO_OFFSET_DATE_TIME` **omiten los segundos cuando valen cero**
(`2026-09-08T14:03-03:00`). El contrato pide precisión de segundos siempre, así que una de
cada sesenta líneas de bitácora habría salido con otro formato — y el cruce con el log del
balanceador se rompe justo en esa. Va un patrón explícito `yyyy-MM-dd'T'HH:mm:ssXXX`.

**Zona horaria fija y no la del sistema.** Si una casa tuviera el reloj en UTC y otra en
`-03:00`, los timestamps no se podrían cruzar entre casas.

**`testOnBorrow` en el pool de Redis.** Sin eso, si la base se cae y vuelve, el pool sigue
repartiendo las conexiones muertas de antes y la réplica queda dando `UNAVAILABLE` para
siempre: hay que reiniciarla a mano. Con `testOnBorrow` el pool hace `PING` antes de
entregar la conexión, descarta la rota y la réplica se recupera sola. Lo encontramos
probando *caída y vuelta* de la base, no la caída sola.

**`ENTRYPOINT` en forma exec.** Con la forma shell el PID 1 sería `/bin/sh`, el `SIGTERM`
de `docker stop` no llegaría a la JVM y el graceful shutdown no correría nunca: el proceso
moriría de golpe por `SIGKILL` a los 10 s.

**`HEALTHCHECK` con nuestro propio cliente Java** y no con `grpc_health_probe`. Cuesta una
JVM cada 10 s, pero no agrega una descarga externa a la imagen y usa exactamente el mismo
stub que el resto del proyecto.

**El estado en Redis**, con el esquema de claves y el script Lua de `CONTRATO.md §4`, byte
por byte el mismo que la App Python: si difirieran, las dos apps escribirían en la misma
base sin encontrar lo del otro.

**La bitácora al disco local**, un archivo por réplica: dos réplicas del mismo nodo
escribiendo el mismo archivo no se pueden distinguir después, y distinguirlas es lo que la
auditoría de la Etapa 2 tiene que demostrar.

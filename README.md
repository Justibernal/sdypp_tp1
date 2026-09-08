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
| ✅ | Conmutador propio (**Plan B**) con round-robin y health checks gRPC | Reparto 50/50 exacto |
| ✅ | Deploy v5→v6 con un loop de 6000 requests corriendo | **6000/6000 OK, 0 perdidas** |
| ✅ | Réplica muerta → sale de rotación, el loop sigue | 100/100 OK con una réplica caída |
| ⬜ | Réplicas repartidas entre las tres casas (Tailscale) | |
| ✅ | Diagramas: arquitectura por etapa, flujo del deploy y secuencias | [`docs/diagramas.md`](docs/diagramas.md) |
| ✅ | Auditoría punto por punto contra el contrato v2.2 | 2 huecos encontrados y cerrados |

---

## Diagramas

En [`docs/diagramas.md`](docs/diagramas.md) — GitHub los renderiza solo. Son siete:
arquitectura de las Etapas 1, 2 y 3; el flujo del deploy con abort y rollback; la secuencia
del reparto con ejección por health check; la del alta atómica con dos réplicas
compitiendo; y el hallazgo del L4.

Las imágenes sueltas están en `docs/img/` (PNG y SVG, para pegar en la presentación) y
`docs/diagramas.html` es un visor para proyectar. Los tres salen del mismo `.md`:

```bash
python3 docs/render.py    # regenera el .html
npx -p @mermaid-js/mermaid-cli mmdc -i docs/diagramas.md -o docs/img/diagramas.md -e png -b white -w 1600
```

---

## Auditoría contra el contrato

Se verificó punto por punto, con el sistema corriendo. Todo lo de §1 a §6 pasa: nombre del
servicio (`sdypp.Servicio`), precedencia del puerto (arg CLI > `PORT` > 8080), zona horaria
fija (con `TZ=UTC` el `arrancado` sigue saliendo en `-03:00`), los cinco RPC, la matriz de
validación completa, el esquema de claves de Redis tal cual, el formato de bitácora (cero
líneas fuera de formato, errores incluidos), un archivo por réplica, contenedor no-root,
puerto interno 8080 y `HEALTHCHECK` contra el health estándar.

**Aparecieron dos huecos, los dos cerrados:**

**1. La gracia del contenedor no estaba garantizada.** El contrato (§6) pide que el plazo del
contenedor sea mayor que el `grace` del servidor, o el `SIGKILL` llega en medio del drenado.
Teníamos `grace` = 10 s y el `deploy.sh` pasa `--stop-timeout 15` — pero el default de
`docker stop` es 10 s, no hay instrucción de Dockerfile que lo cambie, y esta versión de
Docker ni siquiera reporta `StopTimeout` en `docker inspect`, así que no se puede verificar
que el flag se haya aplicado. Se bajó el `grace` del servidor a **8 s** (`TP_GRACE`): ahora
la regla se cumple sola aunque nadie pase el flag.

**2. Exponíamos una sola versión del protocolo de reflection.** La App Python expone la
**v1alpha** (`grpc_reflection.v1alpha`) y nosotros sólo la **v1**. Los `grpcurl` viejos hablan
únicamente v1alpha: el verificador del equipo cruzado podría no vernos por una diferencia que
no tiene nada que ver con nuestro servicio. Ahora se registran las dos, verificado con un
cliente de reflection propio contra ambas.

---

## Plan B: el conmutador propio

`ar.edu.unlu.sdypp.planb.Conmutador` — **no reemplaza al balanceador del equipo
Plataforma**. Es el plan B que el enunciado permite declarar: nos deja demostrar las Etapas
1 y 2 desde una sola máquina si la red entre casas, o el balanceador, no llegan.

```bash
CASA=casa-justino TP_LOGS=logs/conmutador \
  java -cp target/app-java.jar ar.edu.unlu.sdypp.planb.Conmutador 8080 9090 localhost:8111,localhost:8112

curl -s localhost:9090/estado
CONMUTADOR_ADMIN=http://localhost:9090/backends ./deploy/deploy.sh desplegar
```

**Plano de datos: proxy TCP (L4).** Reenvía bytes sin entender el protocolo. Un proxy gRPC
real tendría que hablar HTTP/2 y multiplexar streams. Lo que se paga por el camino corto:
la bitácora registra **conexiones**, no operaciones, y el reparto es **por conexión**.

**Plano de control: HTTP/JSON**, con el `com.sun.net.httpserver` del JDK — el mismo de la
App de la Clase 1. Separar los dos planos es lo que permite cambiar de destino **sin
reiniciar**: el `deploy.sh` le habla al admin mientras el proxy sigue atendiendo. El admin
escucha sólo en `127.0.0.1`: un POST a `/backends` redirige todo el tráfico del servicio.

### Lo que rompimos y aprendimos midiendo

**El balanceador se cayó solo bajo su propia carga: 59,2% de fallos.** No se cayó ninguna
réplica — se cayó el balanceador. Tres causas, las tres reales:

1. **Bitácora sincronizada a disco y `println` en cada conexión.** A ~1200 conexiones/s eso
   es un candado global más una syscall por línea: el proxy pasaba más tiempo hablando con
   el disco que reenviando bytes. Ahora el writer queda abierto y se vuelca cada segundo.
2. **Un solo timeout bastaba para declarar muerto un backend.** Con la JVM ahogada, el
   health check se pasaba de deadline y el pool quedaba vacío **con las dos réplicas
   sanas**. Ahora hacen falta 3 fallos seguidos para expulsar, y 1 acierto para reincorporar:
   expulsar rápido es caro porque achica el pool, reincorporar rápido es barato.
3. **Un canal gRPC nuevo en cada chequeo**, cada 3 s por réplica: un handshake HTTP/2
   completo cada vez. Ahora hay un canal por backend, abierto una vez.

Con eso: **59,2% → 0,7% de fallos**. El 0,7% restante no era del balanceador: el verificador
abría una conexión TCP por request a ~600/s y **desbordaba la cola de accept del sistema
operativo** (`kern.ipc.somaxconn` = 128). El kernel rechazaba conexiones de réplicas
perfectamente sanas. Un cliente gRPC real reusa el canal y nunca se comporta así — medir a
ese ritmo mide el kernel, no el servicio. El verificador acepta una pausa por eso.

A 65 req/s, que es un ritmo de demo honesto: **6000 de 6000, cero perdidas**, con un deploy
completo y un rollback en el medio.

### El hallazgo que anticipa el contrato (§7.3)

| Modo del verificador | Reparto observado |
| :--- | :--- |
| `canal` — un canal compartido, como un cliente gRPC real | **100 % a una sola réplica** |
| `conexion` — un canal nuevo por request, como N clientes | **50 % / 50 % exacto** |

Un balanceador L4 decide **por conexión**. Una conexión gRPC es persistente y multiplexada:
el cliente abre un canal y manda todos sus RPC por ahí, así que queda pegado a una réplica
para siempre. El contrato lo avisa en §7.3 y acá está medido. Para repartir **por RPC** hay
que subir a L7 y entender HTTP/2 — que es la decisión grande que le queda al equipo
Plataforma.

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

# El código, explicado

Qué hay en cada archivo, qué hace cada método y **por qué está así**. Pensado para poder
defender cualquier línea si la preguntan.

---

## Mapa de la carpeta

```
~/sdypp_servJava
├── CONTRATO.md                  ← contrato v2.2 · copia idéntica de la del equipo Python
├── pom.xml                      ← build: dependencias y plugins
├── mvnw · .mvn/                 ← Maven Wrapper: fija la versión de Maven en el repo
├── Dockerfile                   ← imagen: build multi-stage, no-root, HEALTHCHECK gRPC
├── deploy/deploy.sh             ← blue-green con abort y rollback
├── docs/                        ← diagramas, guías, esto
└── src/main/
    ├── proto/contrato.proto     ← el esquema. De acá se GENERA código Java
    └── java/ar/edu/unlu/sdypp/
        ├── AppJava.java             main del servidor
        ├── Config.java              variables de entorno y constantes de contrato
        ├── ServicioImpl.java        los cinco RPC
        ├── RepositorioPersonas.java Redis: esquema de claves y alta atómica
        ├── Bitacora.java            log a disco
        ├── Cliente.java             CLI para probar a mano
        ├── Verificador.java         el de la demo: mide y cuenta
        ├── Healthcheck.java         lo que corre el HEALTHCHECK del contenedor
        └── planb/Conmutador.java    balanceador propio (Plan B)
```

---

## Las librerías: qué hace cada una y por qué esa

| Librería | Versión | Qué hace | Por qué |
| :--- | :--- | :--- | :--- |
| **`protobuf-maven-plugin`** | 0.6.1 | Lee `contrato.proto` y **genera código Java**: las clases de mensajes y el esqueleto del servicio | No escribimos ni una línea de serialización. El `.proto` es la fuente de verdad |
| **`os-maven-plugin`** | 1.7.1 | Detecta SO y arquitectura (`osx-aarch64`, `linux-x86_64`) para bajar el `protoc` correcto | Nadie instala `protoc` a mano; en Linux baja el de Linux |
| **`grpc-netty-shaded`** | 1.84.0 | El transporte: HTTP/2, multiplexado, sockets | gRPC **no** anda sobre HTTP/1.1. *Shaded* = trae su Netty renombrado adentro, no puede chocar con otro |
| **`grpc-protobuf` / `grpc-stub`** | 1.84.0 | El puente entre Protobuf y gRPC, y las clases base de los stubs | Las necesita el código generado |
| **`grpc-services`** | 1.84.0 | `HealthStatusManager` (health estándar) y `ProtoReflectionService` (reflection) | Dos requisitos del contrato, ya hechos y probados |
| **`protobuf-java`** | 4.36.1 | El runtime de Protobuf: serializar/deserializar los mensajes | Lo usa el código generado |
| **`Jedis`** | 7.5.3 | Cliente de Redis | Sincrónico: encaja con el modelo "un hilo por RPC" del servidor. La alternativa (Lettuce) es asíncrona y no aporta nada acá |
| **`slf4j-api` + `slf4j-simple`** | 2.0.19 | Logging de las librerías | Jedis arrastra la 1.7.36; sin fijar la 2.x el logger no engancha y cada réplica arranca escupiendo tres líneas de error |
| **`maven-shade-plugin`** | 3.6.0 | Empaqueta todo en **un** `.jar` ejecutable | Un archivo que se copia y se corre. Además fusiona los `META-INF/services`, sin los cuales gRPC no arranca |
| **`annotations-api`** | 6.0.53 | Sólo en compilación | Los stubs generados llevan `@javax.annotation.Generated`, que no está en el JDK 17 |

**Del JDK, sin librería:** `com.sun.net.httpserver` (el plano de control del conmutador —
el mismo de la App de la Clase 1), `java.net.ServerSocket` (el proxy TCP),
`java.util.concurrent` (pools, `AtomicInteger`, `CountDownLatch`, `CyclicBarrier`) y
`java.time` (los timestamps).

---

## El código generado — lo que NO escribimos

De `contrato.proto`, `protoc` genera en `target/generated-sources/`:

| Qué genera | Para qué sirve |
| :--- | :--- |
| `Persona`, `NuevaPersona`, `Instancia`, `EstadoSalud`, … | Una clase por mensaje, **inmutable**, con su `Builder`. `Persona.newBuilder().setId(7).build()` |
| `ServicioGrpc.ServicioImplBase` | La clase abstracta que extendemos para escribir el servidor |
| `ServicioGrpc.newBlockingStub(canal)` | El cliente: llamás `stub.identidad(...)` y por debajo viaja por la red |

**Por qué esto importa:** el tipado elimina trece casos borde que con JSON había que validar
a mano (legajo como string, decimal, booleano, mayor a int32, cuerpo no-JSON…). El stub del
cliente los rechaza **antes de salir a la red**.

---

## `Config.java` — la configuración

Todo lo que llega por variable de entorno, más las constantes que son contrato.

| Método | Qué hace |
| :--- | :--- |
| `puerto(String[] args)` | Devuelve el puerto: primer argumento de CLI → `PORT` → `8080`. Ese orden es contrato |
| `ahoraIso()` | El timestamp del formato del contrato |
| `urlSinCredenciales(String)` | Recorta la contraseña de `TP_REDIS_URL` antes de escribirla en un log |
| `env(clave, default)` | Lee una variable de entorno tratando `""` como ausente |
| `hostnameDelSistema()` | Fallback de `HOST_NAME` |

**Las dos decisiones que importan:**

**Zona horaria fija, no la del sistema.** Si una casa tuviera el reloj en UTC y otra en
`-03:00`, los timestamps de las bitácoras no se podrían cruzar entre casas.

**El formato del timestamp se escribe a mano:**

```java
DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
```

`OffsetDateTime.toString()` y `ISO_OFFSET_DATE_TIME` **omiten los segundos cuando valen
cero** (`2026-09-08T14:03-03:00`). Una de cada sesenta líneas habría salido con otro formato
—y el cruce con el log del balanceador se rompe justo en esa.

---

## `AppJava.java` — el servidor

Un solo método, `main`, que hace cuatro cosas:

**1. Arma el servidor:**

```java
Grpc.newServerBuilderForPort(puerto, InsecureServerCredentials.create())
    .executor(hilos)                                  // pool fijo, TP_WORKERS
    .addService(new ServicioImpl(repositorio))        // nuestros 5 RPC
    .addService(salud.getHealthService())             // grpc.health.v1.Health
    .addService(ProtoReflectionServiceV1.newInstance())   // reflection v1
    .addService(ProtoReflectionService.newInstance())     // reflection v1alpha
    .build().start();
```

- **`InsecureServerCredentials`** — sin TLS a propósito: el cifrado entre casas lo pone
  Tailscale por debajo. Es lo que dice el contrato §1.
- **Pool fijo y no `newCachedThreadPool`** — con hilos ilimitados, una ráfaga del verificador
  abre cientos de hilos y la réplica se cae por memoria en vez de encolar.
- **Las dos versiones de reflection** — la App Python expone **v1alpha**; los `grpcurl`
  viejos hablan sólo esa y los nuevos prueban **v1** primero. Exponiendo una sola, el
  verificador del equipo cruzado podría no vernos por algo ajeno a nuestro servicio.

**2. Marca la instancia como sana** en el health estándar (`""` y `"sdypp.Servicio"`).

**3. Imprime el banner** — qué instancia es, contra qué base, dónde escribe la bitácora.

**4. Registra el apagado digno** (`shutdownHook`, que corre con `SIGTERM` de `docker stop` y
con `Ctrl+C`):

```
enterTerminalState()   → NOT_SERVING: el balanceador la saca de rotación
shutdown()             → deja de aceptar RPCs nuevos
awaitTermination(8s)   → espera a los que están en curso
shutdownNow()          → si alguno se pasó, corta
```

**Por qué 8 segundos y no 10:** el contrato pide que el plazo del **contenedor** sea mayor
que el del servidor, o el `SIGKILL` llega en medio del drenado. El default de `docker stop`
es 10 s y no hay instrucción de Dockerfile que lo cambie. Con 8 la regla se cumple sola
aunque nadie pase `--stop-timeout`.

---

## `ServicioImpl.java` — los cinco RPC

Extiende `ServicioGrpc.ServicioImplBase`, la clase generada. Cada RPC tiene la misma forma:

```java
public void identidad(IdentidadPedido pedido, StreamObserver<Instancia> respuesta)
```

**`StreamObserver` y no un `return`:** gRPC soporta streaming, así que la respuesta se
*empuja*. En un RPC unario son dos llamadas: `onNext(mensaje)` y `onCompleted()`.

| Método | Qué hace |
| :--- | :--- |
| `identidad` | Devuelve app, lenguaje, equipo, versión, host y arranque |
| `salud` | `status: SANO` — un **enum**, no un string: con texto libre una implementación manda `"ok"` y la otra `"OK"` |
| `echo` | Devuelve el `ping`. Vacío → `INVALID_ARGUMENT` |
| `listarPersonas` | Las personas ordenadas por id. Sin base → `UNAVAILABLE` |
| `crearPersona` | El alta, con la validación en el orden del contrato |
| `responder(...)` | `onNext` + `onCompleted` |
| `fallar(...)` | Registra en la bitácora y corta el RPC con el código del contrato |
| `sinBase(...)` | El `UNAVAILABLE` de los RPC de personas |

**El orden de validación es contrato:** nombre presente → legajo en rango → largo ≤ 120 →
legajo no repetido. Ante un mensaje con dos problemas, las dos implementaciones tienen que
devolver **el mismo** error, no cada una el que detectó primero.

**Los errores viajan tipados:**

```java
Status.INVALID_ARGUMENT.withDescription("legajo fuera de rango").asRuntimeException()
```

No hay números HTTP: son códigos de gRPC (`OK`, `INVALID_ARGUMENT`, `ALREADY_EXISTS`,
`UNAVAILABLE`).

**Sin base se falla, no se devuelve lista vacía:** el cliente no podría distinguir "no hay
personas cargadas" de "no pude leerlas".

---

## `RepositorioPersonas.java` — el estado en Redis

| Método | Qué hace |
| :--- | :--- |
| `crear()` *(estático)* | Arma el pool. Sin `TP_REDIS_URL` devuelve `null` y los RPC de personas dan `UNAVAILABLE` |
| `listar()` | `ZRANGE` del índice + un `HGETALL` por persona, en **pipeline** |
| `crear(nombre, legajo)` | Ejecuta el script Lua. Devuelve el `id`, o `null` si el legajo ya estaba |

**El esquema de claves es contrato tanto como el `.proto`:**

```
personas:seq       string   contador; INCR devuelve el id de la próxima
persona:<id>       hash     nombre, legajo
personas:index     zset     miembro y score <id>; mantiene el orden
legajo:<legajo>    string   <id>; detecta duplicados
```

Si guardáramos bajo otra clave, las dos apps escribirían en la misma base sin encontrar lo
del otro.

**El alta va en un script Lua** porque tiene que ser atómica de punta a punta:

```lua
if redis.call('EXISTS', clave_legajo) == 1 then return -1 end
local id = redis.call('INCR', 'personas:seq')
redis.call('HSET', 'persona:' .. id, 'nombre', nombre, 'legajo', legajo)
redis.call('ZADD', 'personas:index', id, id)
redis.call('SET', clave_legajo, id)
return id
```

Entre comprobar que el legajo no está y escribirlo, otra réplica puede colarse; y entre pedir
el `id` y usarlo, otra puede pedir el mismo. Redis corre el script **entero** sin intercalar
comandos de otros clientes. **Medido: 25 altas del mismo legajo a la vez → 1 `OK` y 24
`ALREADY_EXISTS`.**

**`testOnBorrow(true)` en el pool.** Sin eso, si la base se cae y vuelve, el pool sigue
repartiendo las conexiones muertas y la réplica queda dando `UNAVAILABLE` **para siempre** —
hay que reiniciarla a mano. Con `testOnBorrow` hace `PING` antes de entregar la conexión.
Lo encontramos probando *caída **y vuelta***, no la caída sola.

**Pipeline en `listar()`:** un round-trip por persona multiplicaría la latencia por la
cantidad de filas.

---

## `Bitacora.java` — el log a disco

| Método | Qué hace |
| :--- | :--- |
| `registrar(rpc, codigo)` | Una línea, sin id |
| `registrar(rpc, codigo, id)` | Una línea con `id=<n>` |
| `archivo()` | La ruta, para imprimirla en el banner |

```
2026-09-08T14:03:22-03:00 | java@casa-agustina | CrearPersona | OK | id=7
```

**Al disco local y no a la base:** si la bitácora dependiera de Redis, una caída de la base
dejaría al nodo sin poder registrar nada — justo cuando más falta hace.

**Un archivo por réplica** (`bitacora-<HOST_NAME>.log`): dos réplicas del mismo nodo
escribiendo el mismo archivo no se pueden distinguir después, y distinguirlas es lo que la
auditoría tiene que demostrar.

**`synchronized` sobre un candado propio:** varios hilos del pool gRPC escriben a la vez y
sin exclusión mutua dos líneas se intercalan a mitad de renglón. Es la sección crítica del
enunciado, acá en chiquito.

---

## `Cliente.java` — probar a mano

Con gRPC no alcanza un `curl`: el mensaje va en binario sobre HTTP/2 y hace falta un stub.

```bash
c localhost:8080 identidad | salud | health | echo <texto> | personas | alta <nombre> <legajo>
```

Abre **un canal** (`ManagedChannel`) y lo reusa — así habla un cliente gRPC real. Los errores
llegan como `StatusRuntimeException`, de donde salen el código y la descripción.

---

## `Verificador.java` — el de la demo

El enunciado pide que la demo **se mida** y que la corra otro equipo.

| Modo | Qué hace |
| :--- | :--- |
| `carga <destino> <N> [hilos] [canal\|conexion] [pausaMs]` | N requests; cuenta códigos y reparto por instancia |
| `concurrencia <destino> <legajo> <N>` | N altas del **mismo** legajo a la vez |
| `secuencia <destino> <legajo>` | Un alta y la lectura siguiente |

**`ConcurrentHashMap` para los contadores:** varios hilos cuentan sobre el mismo mapa y un
`HashMap` se corrompe con escrituras concurrentes.

**`CyclicBarrier` en `concurrencia`:** todos los hilos esperan en la misma barrera y salen
juntos. Si se lanzaran de a uno, el primero terminaría antes de que arranque el segundo y no
habría carrera que medir.

**El modo `canal` vs `conexion`** es el hallazgo del contrato §7.3: con canal compartido, un
balanceador L4 manda **el 100 % a una sola réplica**; con canal por request, **50/50**.

**La pausa** existe porque sin ella el verificador abre conexiones tan rápido que desborda la
cola de accept del sistema operativo (`somaxconn` = 128) y el balanceador ve "connection
refused" de réplicas perfectamente sanas: eso mide el kernel, no el servicio.

---

## `Healthcheck.java` — lo que corre el contenedor

Consulta `grpc.health.v1.Health` —el estándar, no nuestro RPC `Salud`— porque es **el mismo
chequeo que hace el balanceador**: así el contenedor y el balanceador no pueden opinar
distinto sobre si la réplica está sana. Sale `0` si `SERVING`, `1` en cualquier otro caso.
No se puede usar `curl`: no hay HTTP que consultar.

---

## `planb/Conmutador.java` — el balanceador propio

**No reemplaza al de Plataforma.** Es el plan B declarado del enunciado.

| Método | Qué hace |
| :--- | :--- |
| `main` | Abre el `ServerSocket` público y acepta conexiones |
| `atender(socket)` | Elige backend, abre socket al backend y bombea en los dos sentidos |
| `elegir()` | Round-robin sobre los **sanos** |
| `bombear(desde, hacia)` | Copia bytes de un socket al otro hasta que se cierre |
| `arrancarChequeosDeSalud()` | Cada 3 s, `grpc.health.v1.Health` a cada backend |
| `consultarSalud(backend)` | El chequeo, con deadline de 2 s |
| `arrancarAdmin(puerto)` | El `HttpServer` del plano de control |
| `reemplazarBackends(lista)` | Cambia el destino **sin reiniciar** |
| `bitacora(...)` / `volcar()` | El log del balanceador, con writer abierto y volcado cada segundo |

**Dos planos separados.** El de datos reenvía bytes en el `8080`; el de control es un
`HttpServer` en el `9090`, atado a `127.0.0.1`. Esa separación es lo que permite conmutar sin
reiniciar. El control no se expone a la red: un POST a `/backends` redirige **todo** el
tráfico del servicio.

**L4 y no L7.** Reenvía bytes sin entender el protocolo. Un proxy gRPC real tendría que
hablar HTTP/2 y multiplexar streams. Lo que se paga: la bitácora registra **conexiones**, no
operaciones, y el reparto es **por conexión**.

**Dos hilos por conexión.** TCP es full-duplex: los dos sentidos van a la vez. Si fuera
secuencial, el proxy se trabaría esperando a que un lado termine de hablar.

**`AtomicInteger` para el round-robin.** Es el dato compartido de la pregunta 4 del
enunciado: con un `int` común, dos hilos leen el mismo valor, mandan al mismo backend y un
incremento se pierde. `getAndIncrement` es atómico.

**`CopyOnWriteArrayList` para los backends.** El hilo del admin reemplaza la lista entera
mientras decenas de hilos la recorren; con un `ArrayList` común, conmutar en medio de una
ráfaga tira `ConcurrentModificationException`.

**Umbral de 3 fallos seguidos para expulsar, 1 acierto para volver.** Con umbral 1, un pico
de latencia o un GC bastan para expulsar una réplica sana — nos pasó: bajo carga los chequeos
se pasaban de deadline y el pool quedaba vacío **con las dos réplicas vivas**. Expulsar
rápido es caro porque achica el pool; reincorporar rápido es barato.

**Reintento corto antes de rechazar.** Durante una conmutación hay milisegundos en los que el
pool nuevo todavía no pasó su primer chequeo. Rechazar en seco ahí convierte el deploy sin
downtime en un deploy con downtime cortito, que es lo mismo.

---

## `Dockerfile`

**Multi-stage.** La etapa 1 (`maven:3.9-eclipse-temurin-17`) compila; la etapa 2
(`eclipse-temurin:17-jre`) sólo se lleva el `.jar`. El build corre **adentro de Docker**: la
imagen no depende del JDK de la máquina de nadie.

**El `pom.xml` se copia solo primero** y se bajan las dependencias: mientras no cambien, esa
capa queda cacheada y un cambio de código no vuelve a bajar medio Maven Central.

**Usuario no-root** (`app`, uid 10001).

**`HEALTHCHECK` contra el health gRPC**, con `start-period=20s` porque la JVM tarda en
arrancar.

**`ENTRYPOINT` en forma exec** (`["java", "-jar", ...]`). Con la forma shell el PID 1 sería
`/bin/sh`, el `SIGTERM` de `docker stop` no llegaría a la JVM y el graceful shutdown no
correría nunca: el proceso moriría de golpe por `SIGKILL`.

---

## `deploy/deploy.sh`

| Comando | Qué hace |
| :--- | :--- |
| `desplegar` | Construye, levanta el color libre **al lado**, verifica y sólo entonces conmuta |
| `rollback` | Vuelve al color anterior, que sigue corriendo |
| `estado` | Qué color sirve y cómo está cada réplica |
| `bajar` | Baja todo |

**Funciones internas:** `version_declarada` (saca la versión del código, no de un argumento),
`construir`, `levantar_color`, `verificar_color`, `bajar_color`, `conmutar_a`.

**Dos verificaciones, no una.** El health check dice "estoy vivo", no "soy la versión que
pediste": un ship a medias deja un contenedor sano corriendo la versión anterior. El segundo
verify compara la versión que responde `Identidad` contra la declarada en `Config.VERSION`.

**Todas las réplicas a la vez, no de a una.** El deploy secuencial deja un rato con una sola
réplica vieja en rotación: si esa se cae justo ahí, no queda nada sirviendo.

**La vieja no se baja al conmutar.** Volver atrás tiene que ser un comando, no un deploy en
reversa.

**`conmutar_a` está aislada a propósito:** es el **único** punto que depende del equipo
Plataforma. Cuando definan la firma del endpoint se cambia ahí y nada más.

**El tag lleva versión y commit** (`v7-7f0cef4`), con sufijo `-sucio` si hay cambios sin
commitear: una imagen que no se puede volver a construir igual tiene que gritarlo desde el
nombre.

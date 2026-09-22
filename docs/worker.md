# El worker de cola

Un servicio que **toma un pedido de la cola, lo resuelve y devuelve la respuesta**. Es el
mismo servicio del contrato alcanzado por otro camino: donde el servidor gRPC atiende una
conexión, el worker va a buscar trabajo.

Lo que hay de nuestro lado está en [`CONTRATO.md §8`](../CONTRATO.md); la especificación del
transporte la publica el equipo de colas en
[`contrato-worker.md` v1](https://github.com/SDyPPTpGrupal/sdypp_balanceador/blob/feature/desacople/docs/contrato-worker.md),
y lo que ve el cliente final, en el
[contrato público](https://github.com/SDyPPTpGrupal/sdypp_balanceador/blob/feature/desacople/docs/contrato-publico.md).
La arquitectura, en los diagramas [8 y 9](diagramas.md).

```
                  ┌───── POST /pedidos/tomar {consumidor, espera} ─────┐
   balanceador ──▶│  COLA  │  (clúster: sólo el master atiende)        ▼
                  │ master │◀──────── POST /respuestas ───────── WORKER ──▶ Redis
   balanceador ◀──└────────┘                                        └──▶ bitácora
                       ▲
                  GET /health  ──  quién es el master (sin token)
```

---

## Lo que lo define

**Nadie le habla.** El worker no expone un puerto de servicio: es él quien pide trabajo. Por
eso se despliega en cualquier casa sin pedirle al equipo de red una IP alcanzable ni un
puerto abierto — sólo necesita *alcanzar* la cola. Lo único que escucha es un panel de sólo
lectura para el `HEALTHCHECK` y para mirarlo en la demo.

**Nadie reparte el trabajo.** El worker libre toma el próximo pedido. Levantar otro worker
es toda la configuración que hace falta para escalar: no hay que tocar ni la cola ni el
balanceador, y un worker lento toma menos porque pide menos — cosa que un round-robin no
sabe hacer.

**Una sola implementación del contrato.** El worker y las réplicas gRPC resuelven con la
misma clase `Operaciones`. Si cada uno validara por su cuenta, la misma alta daría un código
por un camino y otro por el otro, y el contrato dejaría de valer apenas cambia el transporte.

---

## Levantarlo

### Con el servicio de cola del otro equipo

> **El paso a paso está en [`levantar-worker.md`](levantar-worker.md)**, con qué tenés que
> ver en cada bloque y una tabla de síntomas. Este documento es el porqué; ese, el cómo.

La cola y la base están en la **tailnet**, así que la máquina tiene que estar adentro antes
que nada. `diagnostico` lo dice en cinco líneas y es lo primero que hay que correr: sin red,
el worker levanta igual y se queda reintentando para siempre — un síntoma mucho menos claro
que un error al arrancar.

```bash
cp deploy/.env.ejemplo .env && $EDITOR .env    # seed list, token y tu IP de Tailscale

./deploy/worker.sh diagnostico    # ¿se alcanzan los nodos de cola y la base?
./deploy/worker.sh levantar 2     # construye y levanta 2 workers
./deploy/worker.sh estado         # qué hay corriendo y cuánto resolvió cada uno
./deploy/worker.sh escalar 4      # deja exactamente 4
./deploy/worker.sh bitacora       # las últimas líneas de cada uno
./deploy/worker.sh bajar
```

**Un clúster, y un solo nodo que atiende.** `TP_COLA_URLS` es una *seed list*, no una lista de
réplicas equivalentes: sólo el **master** atiende pedidos, y los demás contestan `421` con la
dirección del que manda. El worker lo descubre con `GET /health`, lo cachea y le habla sólo a
él; el caché se invalida solo con un `421` o con una conexión que se corta.

No es un capricho de ellos: `tomar` **no es una lectura**, es una mutación —reserva el pedido
y lo saca del pool—, así que repartirla entre nodos entregaría el mismo pedido dos veces.

### Sin la cola real — con el doble de prueba

`ar.edu.unlu.sdypp.planb.ColaFalsa` habla el **mismo contrato v1**: las rutas, el
`X-Cola-Token`, el `421 no-soy-master` y los dos `409`. **No es la cola del sistema**, y sigue
existiendo aunque la cola ya esté publicada, porque es lo único contra lo que se pueden
provocar los casos feos a voluntad: apagar el master, tener un slave que redirige, vencer una
reserva o declarar un contrato incompatible.

```bash
./mvnw -q package

# 1) un master, y un slave que redirige a él (dos pestañas)
export TP_COLA_TOKEN=loquesea
java -cp target/app-java.jar ar.edu.unlu.sdypp.planb.ColaFalsa 8000
java -cp target/app-java.jar ar.edu.unlu.sdypp.planb.ColaFalsa 8001 slave http://127.0.0.1:8000

# 2) un worker, con el SLAVE primero en la seed list para que tenga que descubrir el master
TP_COLA_URLS=http://127.0.0.1:8001,http://127.0.0.1:8000 \
  TP_COLA_CONSUMIDOR=127.0.0.1:8111 HOST_NAME=casa-justino-worker-1 CASA=casa-justino \
  TP_LOGS=logs/worker-1 \
  java -cp target/app-java.jar ar.edu.unlu.sdypp.worker.Worker

# 3) publicar tareas y recolectar las respuestas, como haría el balanceador
java -cp target/app-java.jar ar.edu.unlu.sdypp.planb.ColaFalsa \
  publicar http://127.0.0.1:8000 40 "balanceador@casa-justino"
```

Para ver el reparto, levantar un segundo worker con otro `TP_COLA_CONSUMIDOR` y otro
`TP_COLA_ADMIN`. Para probar que el worker se planta ante un contrato incompatible, levantar
la cola con `TP_COLA_CONTRATO_FALSO=2.0`.

---

## Variables de entorno

| Variable | Para qué | Default |
| :--- | :--- | :--- |
| `TP_COLA_URLS` | **Seed list** del clúster, separada por coma. No son réplicas equivalentes: sólo el master atiende. **Obligatoria** | vacío → no arranca |
| `TP_COLA_TOKEN` | Token de consumidor. Va en cada request salvo `/health` | vacío → no se manda ninguna |
| `TP_COLA_CONSUMIDOR` | El `host:puerto` **gRPC** de esta réplica. **Obligatoria** — ver abajo | vacío → **no arranca** |
| `TP_COLA_CONTRATO` | Major del contrato que implementa el worker. Si `/health` declara otro, no arranca | `1` |
| `TP_COLA_AUTH` | Nombre del header de la credencial | `X-Cola-Token` |
| `TP_COLA_ESQUEMA` | Prefijo del valor. Vacío manda el token pelado | vacío |
| `TP_COLA_HILOS` | Consumidores dentro de este worker | `2` |
| `TP_COLA_ESPERA` | Segundos de long-polling. Se recorta al techo de 30 que fija el contrato | `20` |
| `TP_COLA_ADMIN` | Puerto del panel | `9091` |
| `TP_REDIS_URL` | Base compartida, con contraseña: `redis://:<pass>@<host>:6379/0`. Sin ella, las tareas de personas responden `UNAVAILABLE` | vacío |
| `HOST_NAME` · `CASA` · `TP_LOGS` · `TP_GRACE` | Como en el servidor gRPC | ídem |

Las seis primeras son **especificación del otro equipo**, y por eso son variables y no
constantes: el día que la cola cambie de header o de nodos, se edita el `.env` de la casa y
el worker levanta con lo nuevo — sin recompilar ni reconstruir la imagen.

**`TP_COLA_CONSUMIDOR` no tiene default, y es a propósito.** El contrato pide el
`host:puerto` gRPC de la réplica porque es el string con el que el balanceador la tiene
registrada, y el que publica en su `/health` como "réplicas que están consumiendo". Un
identificador inventado —el viejo `java@casa-justino-worker-1`, por ejemplo— deja a la
réplica figurando **sana y sin consumir nada**, y eso no se diagnostica solo. Es preferible
que el worker no arranque. `deploy/worker.sh` lo arma como `$TP_COLA_HOST:811n`, que son los
puertos de las réplicas gRPC del color azul.

El `.env` va en la raíz y está en `.gitignore`; `deploy/.env.ejemplo` dice qué completar.
El token y la contraseña **no van al repo**.

## El panel

```bash
curl -s localhost:9101/estado    # contadores, reparto por operación, qué tiene en vuelo
curl -s localhost:9101/salud     # lo que consulta el HEALTHCHECK
```

```json
{"consumidor":"100.101.15.93:8111","hilos":1,"colaSana":true,
 "cola":"http://100.78.246.64:8085 · http://100.91.134.43:8085 · http://100.120.186.92:8085",
 "maestro":"http://100.91.134.43:8085","colaConToken":true,
 "tomadas":76,"resueltas":38,"fallidas":38,"descartadas":0,"saturadas":0,"perdidas":0,
 "porOperacion":{"CrearPersona":22,"Salud":15,"ListarPersonas":14,"Echo":12,"Identidad":12},
 "enVuelo":[],"ultimaTarea":"3947f03b · Identidad · OK"}
```

`maestro` es el campo que más se mira cuando el worker deja de tomar trabajo: es lo único que
no se puede deducir de la configuración. `null` quiere decir que el clúster está en elección
o que no contesta nadie.

---

## Decisiones

**`tomar` es de long-polling y no polling corto.** El que pide trabajo se queda esperando
—hasta 30 s, que es el techo del contrato— y lo despiertan apenas hay algo. Con polling cada
200 ms serían cientos de requests por minuto sin trabajo, y cada pedido se vería con hasta
200 ms de retraso.

**Se reintenta `responder`, nunca `tomar`.** Es la trampa que el contrato marca y que no se
ve sola: si la conexión se corta *después* de que la request salió, no sabemos si la cola la
procesó. Repetir un `responder` es inofensivo —el segundo vuelve con `409 desconocido`— pero
repetir un `tomar` puede dejar **dos pedidos reservados** de los cuales sólo vimos uno. Por
eso `tomar` sólo se repite cuando el fallo garantiza que la request no salió
(`ConnectException`, `UnknownHostException`); un timeout no califica.

**El major del contrato se verifica al arrancar, y es fatal.** `/health` declara `contrato`;
si el major no coincide, el worker sale con código 3 en vez de operar. Responder con campos
que ya no significan lo mismo es peor que no responder, porque no se nota. Un clúster que
cambia de contrato con el worker ya corriendo lo para igual, y para todos los hilos, para que
el orquestador lo vea caído en vez de vivo y mudo.

**La cola caída no vuelve enfermo al worker.** `/salud` dice "el worker está trabajando", no
"la cola responde". Un worker que se declara enfermo porque la cola está caída se hace
reiniciar por el orquestador una y otra vez sin arreglar nada — y cuando la cola vuelve, no
hay worker esperando. La salud de la cola se mira en `/estado` (`colaSana`), que para eso
está. Lo que sí hace el worker es **backoff exponencial hasta 15 s**: con la cola caída, N
workers reintentando sin pausa la inundan justo cuando intenta levantarse.

**Se reintenta la entrega, nunca la ejecución.** Cuando el POST falla, la tarea ya se
ejecutó: si el alta se escribió en la base y la respuesta se pierde, el cliente recibe un
error por algo que sí pasó — y como la cola no reintenta las escrituras, nadie lo corrige
después. Reintentar la ejecución, en cambio, duplicaría el trabajo.

**HTTP/1.1 explícito.** El `HttpClient` del JDK negocia HTTP/2 por defecto. La cola es
Python y puede estar sobre un servidor que no lo hable: el intento de upgrade se paga en
cada request y con algunos servidores simples directamente falla. Acá no hay streams que
multiplexar.

**Al apagarse, termina lo que tiene y suelta lo que no empezó.** Sólo se interrumpe a los
consumidores que están esperando trabajo, no a los que están resolviendo: cortarle el hilo a
uno que ya escribió en la base perdería la respuesta de una operación que sí ocurrió. Lo que
tomó y no empezó se devuelve con el `DELETE`, y otro worker lo agarra en el acto — sin eso
hay que esperar a que venza la reserva, y si la operación no es idempotente la cola ni
siquiera la reintenta.

**No hay blue-green para los workers.** El deploy sin cortes del servicio gRPC existe porque
hay un cliente esperando del otro lado de una conexión abierta. A un worker no lo espera
nadie: se baja, la cola le reasigna lo que tenía, y el que entra empieza a tomar pedidos.
**La continuidad la da la cola, no el despliegue** — y eso es, en una línea, lo que se gana
al desacoplar con una cola.

**El worker sale del mismo jar que las réplicas.** Construirlos por separado abriría la
puerta a que estén corriendo versiones distintas del contrato, que es justo lo que el
contrato existe para impedir.

---

## Qué se verificó

Contra el doble de prueba, con dos workers de dos casas simuladas y sin Redis (Docker
Desktop apagado en esa máquina).

| | Qué | Resultado |
| :--- | :--- | :--- |
| ✅ | Ciclo completo tomar → resolver → responder | 101 tareas publicadas, **101 respondidas, 0 perdidas** |
| ✅ | Las cinco operaciones del contrato por la cola | `Identidad`, `Salud`, `Echo`, `ListarPersonas`, `CrearPersona` |
| ✅ | Los casos borde que el JSON reabre | **11/11** con el código y el mensaje exactos del contrato |
| ✅ | Orden de validación de §3 con dos problemas a la vez | El mismo error que da el servidor gRPC |
| ✅ | Operación fuera del catálogo | `UNIMPLEMENTED`, sin excepción ni hilo caído |
| ✅ | Consumidores compitiendo, dos workers | Segunda ronda **21 / 19** sobre 40 tareas |
| ✅ | Cola caída al arrancar | Backoff exponencial, 8 reintentos, se recuperó sola al levantarla |
| ✅ | Base caída | `UNAVAILABLE` por la cola, igual que por gRPC; el resto de las tareas sigue saliendo `OK` |
| ✅ | Bitácora con el sexto campo | **101 líneas, 0 fuera de formato** |
| ✅ | **Worker muerto de golpe con una tarea en la mano** | **3000 de 3000 respondidas, 0 perdidas** |
| ✅ | Reserva vencida → reasignación al otro worker | `reasignados: 1`, y la tarea la terminó el otro |
| ✅ | `TP_REDIS_URL` con contraseña (`redis://:pass@host/0`) | 15/15 `OK`, las claves del §4 en la base |
| ✅ | La contraseña no aparece en el log | `[personas] base compartida en redis://host:puerto` |

Y contra el doble hablando ya el **contrato v1** — seed list con un nodo muerto, un slave y
un master:

| | Qué | Resultado |
| :--- | :--- | :--- |
| ✅ | Descubre el master saltando un nodo caído y preguntándole a un **slave** | `contrato 1.0 · master http://…:8000` |
| ✅ | Token equivocado | `403 {"error":"token inválido"}`, reportado como credencial y no como caída |
| ✅ | Ruta de datos contra un slave | `421 {"error":"no-soy-master","master":"…"}` |
| ✅ | **El master se cae y aparece otro en distinta dirección** | Reenganchó solo: 10/10, **sin reiniciar el proceso** |
| ✅ | Clúster entero caído, 12 s | `colaSana:false`, `maestro:null`, **4 errores** — backoff real, no bucle |
| ✅ | `/health` declara contrato `2.0` | El worker **no arranca**: salida 3 con el motivo |
| ✅ | Las cinco formas del `contenido` contra el contrato público | camelCase exacto, `servidoPor` incluido en `Identidad` |

**El master se cae y el worker reengancha solo.** Se bajó el clúster entero, se esperó, y se
levantó un master nuevo **en otra dirección** (el puerto que antes tenía el slave). El worker
lo encontró recorriendo la seed list y drenó las 10 tareas sin que nadie lo tocara. Los **4
errores en 12 segundos** son la otra mitad del resultado: es el backoff funcionando. Un bucle
cerrado habría dado miles y la CPU al 100%, que es exactamente lo que el contrato marca como
lo único que no se puede hacer.

**Y una que falló, y valía.** En la primera corrida en contenedores el worker quedó con
`maestro: null` y `ConnectException` aunque los dos nodos estaban vivos: el slave anunciaba
al master como `http://127.0.0.1:8000`, y adentro del contenedor eso es el contenedor mismo.
Era un artefacto del doble, pero el hallazgo es real y hay que decirlo en la defensa: **el
worker va a donde el clúster le dice que vaya.** Si los nodos anuncian una dirección que no
se resuelve desde donde corre el worker —un loopback, un nombre de red interna—, no hay nada
que el worker pueda hacer. Es lo primero que hay que mirar si el `/health` de ellos da verde
y el nuestro dice `maestro: null`.

Y en contenedores, con Redis y las réplicas gRPC levantadas:

| | Qué | Resultado |
| :--- | :--- | :--- |
| ✅ | Los dos contenedores llegan a `healthy` con su propio `HEALTHCHECK` | `running · healthy` |
| ✅ | **Alta real contra Redis, por la cola** | `OK`, con el `id` que asignó la base |
| ✅ | El mismo legajo otra vez | `ALREADY_EXISTS · el legajo ya está registrado` |
| ✅ | La lectura por la cola la encuentra | `{"id":1,"nombre":"Ada Lovelace","legajo":710181}` |
| ✅ | **Lo dado de alta por la COLA se lee por gRPC** | La misma persona por los dos caminos |
| ✅ | `docker stop` → SIGTERM → drenado → salida limpia | **2 s**, con las dos líneas del graceful shutdown |
| ✅ | Bitácora del contenedor, con el sexto campo | La línea termina en `tarea=ef427122` |
| ✅ | Con un worker abajo, el otro sigue atendiendo solo | Sin intervención |

**La corrida larga: un worker que se muere de golpe.** 3000 tareas publicadas en flujo
continuo, con un solo consumidor drenándolas (~340 por segundo). A mitad de camino se le
manda `kill -9` al worker, **con una tarea en la mano** — sin señal, sin drenado, sin aviso:
igual que una casa que se queda sin luz. La cola no puede enterarse por él; se entera porque
**le vence la reserva**.

```
al matarlo   enVuelo: 1                      ← la tarea huérfana
al final     reasignados: 1 · respondidos: 3000 · descartados: 0
reparto      worker-a 2361 · worker-b 639     (= 3000)
bitácoras    2361 + 639 = 3000 líneas          sin duplicados
```

**3000 de 3000, cero perdidas.** La tarea huérfana la terminó el otro worker diez segundos
después, que es lo que dura la reserva. Y que las dos bitácoras sumen exactamente 3000 es la
otra mitad del resultado: la tarea se reasignó, pero **no se ejecutó dos veces**.

Es el equivalente, del lado de la cola, a las 12000/12000 con deploy y rollback de la Etapa
1 — con una diferencia que vale la pena decir en la defensa: allá el servicio seguía en pie
porque el deploy tuvo cuidado; acá se murió un proceso de la peor manera posible y **nadie
tuvo que hacer nada**.

**El estado es uno solo, y eso es lo que cierra el trabajo.** Se dio de alta una persona
**por la cola** y apareció **por gRPC**, consultando una réplica que nunca supo de esa tarea.
Dos transportes, dos procesos, dos contenedores distintos, una sola base y un solo contrato.

**El hallazgo del long-polling: el consumidor fantasma.** La primera tarea publicada
*después* de apagar un worker tardó **once segundos** en resolverse, siendo un `Echo`. La
respuesta lo explicaba sola:

```json
"intentos": ["java@casa-justino-worker-1", "java@casa-justino-worker-2"], "esperaMs": 11029
```

El worker 1 ya estaba **apagado**. Lo que seguía vivo era su `GET` colgado **del lado de la
cola**: cuando se publicó la tarea, la cola despertó a ese consumidor —que para ella seguía
esperando trabajo—, se la reservó y trató de escribir la respuesta en un socket que ya no
existía. La tarea quedó en vuelo hasta que venció la reserva. Recién ahí la tomó el worker 2.

**Y es peor de lo que parece: hay un fantasma por hilo.** Al rehacer la prueba con
`TP_COLA_HILOS=2`, la tarea pasó por **los dos** GET colgados del worker apagado —10 segundos
cada uno— antes de llegar a un worker vivo. La cuenta es `hilos × reserva`: con 2 hilos y 10
segundos de reserva, **20 segundos**; con 4 hilos, 40. Lo descubrimos porque la prueba
automatizada, que esperaba 20 segundos, se cayó por un segundo de diferencia.

**No pasa siempre, y eso lo empeora.** En tres corridas iguales medimos 11 s, más de 20 s y
**0 s**: depende de a cuál de los consumidores en espera despierta la cola, que es una
carrera entre los fantasmas y los vivos. Un fallo que aparece dos de cada tres veces y
desaparece justo cuando lo vas a mostrar es exactamente el que no se encuentra el día de la
demo — por eso queda escrito acá y medido, y no como "a veces tarda".

**Y con una escritura no hay reintento.** El `Echo` sobrevive porque es idempotente: la cola
lo reencola cada vez. Un `POST /personas` que caiga en un fantasma se **falla con
`DEADLINE_EXCEEDED` sin haberse ejecutado nunca** — la cola no puede saber que el worker
murió antes de recibirlo. Un alta perfectamente válida le vuelve al cliente como error, y el
disparador fue apagar un worker.

**Del lado del worker no hay nada que hacer.** Cuando se apaga todavía no sabe qué tarea le
van a dar después de muerto, así que tampoco puede devolverla — y cerrar el socket no ayuda:
el servidor no se entera hasta que intenta escribir. Se arregla de dos lados:

* **La cola**, que es donde está el arreglo de verdad: devolver el pedido al frente cuando
  falla la escritura de la respuesta, en vez de esperar a que venza la reserva. Es la
  **pregunta 13**.
* **Nosotros**, mientras tanto, acotando la ventana: `TP_COLA_ESPERA` más corto y
  `TP_COLA_HILOS` más bajo achican `hilos × reserva`. Escalar con **más workers de un hilo**
  en vez de un worker de N hilos deja menos fantasmas por apagado — y ya era la forma
  preferible de escalar, porque un worker de N hilos también es N tareas en riesgo cuando se
  muere.

Conviene decirlo así en la defensa: **la cola no sabe si el que espera sigue vivo hasta que
le intenta hablar.** Es el mismo problema que el balanceador resuelve con health checks cada
tres segundos; acá se paga una sola vez, al apagar un worker, y se paga en latencia — salvo
para las escrituras, que se pagan en un error.

**Un hallazgo del reparto.** La primera ronda dio **34 / 6** y la segunda **21 / 19**, con
las mismas 40 tareas. No es un error: el primer worker ya estaba estacionado en su GET de
long-polling cuando se publicó la ráfaga, así que se despertó primero y siguió tomando de a
una mientras el otro recién arrancaba. **Una cola no reparte por turno, reparte por
demanda**: el que está listo se lleva el trabajo. Con los dos workers ya esperando, el
reparto se empareja solo. Es lo contrario del round-robin del balanceador, que le manda su
turno a una réplica aunque esté ahogada.

---


## Lo que la especificación real cambió

Las siete preguntas que le habíamos dejado al equipo de colas están contestadas, y **casi
ninguna como la habíamos supuesto**. Vale la pena que quede escrito, porque la lección no es
"nos equivocamos": es que estaba todo aislado en un solo lugar y por eso el desfasaje costó
un día y no una reescritura.

| Lo que asumimos (§8 v2.4) | Lo que dice el contrato v1 |
| :--- | :--- |
| `GET`/`POST`/`DELETE` contra la misma URL | `POST /pedidos/tomar` · `POST /respuestas` · `POST /pedidos/devolver` |
| `consumidor` y `espera` en la query | En el cuerpo JSON |
| `Authorization: Bearer <token>` | `X-Cola-Token: <token>` |
| Réplicas equivalentes, rotar entre ellas | **Clúster master/slave**: rotar es un generador de `421` |
| `409` = descartada, punto | **Dos** `409` distintos, que se distinguen por el cuerpo |
| `2xx` al aceptar la respuesta | `202 {"resultado":"entregada"}` |
| `consumidor` = nombre libre (`java@host`) | El `host:puerto` **gRPC** de la réplica, o no sirve |
| `contenido` con los nombres del `.proto` (`servido_por`) | **camelCase** (`servidoPor`): es lo que se le publica al cliente |
| `quedaMs` informativo | **Timeout obligatorio** de la operación |
| Sin versionado | `/health` declara `contrato`, y un major distinto hay que rechazarlo |

**Lo que nos salvó fue el aislamiento, no el acierto.** Todo el transporte vivía en
`ClienteCola`, y todo lo que depende de ellos entraba por variables de entorno. Cambiar de un
contrato al otro tocó esa clase, tres campos de `Ejecutor` y la configuración — no tocó
`Operaciones`, ni el servidor gRPC, ni el esquema de la base, ni la bitácora. La apuesta de
la §8 no era adivinar bien: era **acotar el costo de adivinar mal**, y eso sí salió.

**La que más duele es la rotación.** Repartir el trabajo entre los nodos estaba bien
razonado: si son réplicas, clavarse en una desperdicia dos y concentra el riesgo. El
razonamiento era correcto y la conclusión era incorrecta, porque faltaba un hecho del otro
sistema: `tomar` **no es una lectura**. Reserva el pedido y lo saca del pool, así que
repartirla entre nodos entregaría el mismo pedido dos veces. No había forma de deducirlo
desde nuestro lado — es de esas cosas que sólo se cierran preguntando, que es exactamente
para lo que servía la lista de preguntas abiertas.

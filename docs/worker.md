# El worker de cola

Un servicio que **toma un pedido de la cola, lo resuelve y devuelve la respuesta**. Es el
mismo servicio del contrato alcanzado por otro camino: donde el servidor gRPC atiende una
conexión, el worker va a buscar trabajo.

El contrato lo publica el otro equipo: **`docs/contrato-worker.md` del repo del
balanceador, rama `feature/desacople`** (contrato del worker **v1**). Nuestro lado está
resumido en [`CONTRATO.md §8`](../CONTRATO.md); la arquitectura, en los diagramas
[8 y 9](diagramas.md).

```
                      ┌─── POST /pedidos/tomar ────────────────┐
   balanceador ──▶ ┌──┴───────┐                                ▼
    POST /pedidos  │   COLA   │ ◀── POST /respuestas ──── WORKER ──▶ Redis
   balanceador ◀── │ (master) │ ◀── POST /pedidos/devolver   └──▶ bitácora
  /respuestas/tomar└──────────┘
```

Las tres rutas del worker van con el header `X-Cola-Token` y **siempre contra el master**:
`tomar` reserva el pedido, así que no es una lectura y no se puede repartir entre nodos. El
worker arranca con una lista de nodos, pregunta quién es el master por `/health`, lo cachea,
y cuando cambia se muda solo siguiendo el `421` que le devuelve el nodo viejo.

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

Hacen falta **tres cosas** que las da ellos, no nosotros:

```bash
export CASA=casa-justino
export TP_COLA_URLS=<las-urls-de-los-nodos-separadas-por-comas>
export TP_COLA_TOKEN=<el-token-de-CONSUMIDOR>
export TP_COLA_CONSUMIDOR=<host:puerto-gRPC-de-la-replica>

./deploy/worker.sh levantar 2     # construye y levanta 2 workers
./deploy/worker.sh estado         # qué hay corriendo y cuánto resolvió cada uno
./deploy/worker.sh escalar 4      # deja exactamente 4
./deploy/worker.sh bitacora       # las últimas líneas de cada uno
./deploy/worker.sh bajar
```

### Sin él — con el doble de prueba

Mientras la cola no esté publicada, `ar.edu.unlu.sdypp.planb.ColaFalsa` la reemplaza. **No
es la cola del sistema**: es lo mínimo para tener contra qué hablar, con la misma semántica
—reserva, devolución al frente, presupuesto, regla de reintento— que el módulo del otro
equipo.

`ColaFalsa` **habla el contrato real**: las mismas rutas, los mismos códigos y los mismos
cuerpos. Si hablara otra cosa no serviría para lo único que tiene que servir — probar que el
worker cumple el contrato — y la prueba pasaría en verde mientras el worker no anda contra la
cola de verdad.

```bash
./mvnw -q package
export COLA_TOKEN=secreto-local

# 1) la cola falsa, en el rol de master (dejar corriendo en una pestaña)
java -cp target/app-java.jar ar.edu.unlu.sdypp.planb.ColaFalsa 8000

# 2) un segundo nodo que NO es master: contesta 421 apuntando al primero. Sirve para probar
#    que el worker sigue el redirect en vez de tratarlo como una caída de la cola.
COLA_FALSA_MASTER=http://localhost:8000 \
  java -cp target/app-java.jar ar.edu.unlu.sdypp.planb.ColaFalsa 8010

# 3) un worker (otra pestaña). La seed list arranca por el SLAVE a propósito.
TP_COLA_URLS=http://localhost:8010,http://localhost:8000 \
TP_COLA_TOKEN=$COLA_TOKEN TP_COLA_CONSUMIDOR=127.0.0.1:8080 \
HOST_NAME=casa-justino-worker-1 CASA=casa-justino TP_LOGS=logs/worker-1 \
  java -cp target/app-java.jar ar.edu.unlu.sdypp.worker.Worker

# 4) publicar tareas y recolectar las respuestas, como haría el balanceador
java -cp target/app-java.jar ar.edu.unlu.sdypp.planb.ColaFalsa \
  publicar http://localhost:8000 40 "balanceador@casa-justino"
```

Para ver el reparto, levantar un segundo worker con otro `HOST_NAME` y otro
`TP_COLA_ADMIN`.

Dos variables más del doble, para provocar a voluntad casos que con el servicio real hay que
romper algo para ver: `COLA_FALSA_MASTER` (este nodo hace de slave y contesta `421`) y
`COLA_FALSA_COTA_RESPUESTAS` (a partir de cuántas respuestas sin recolectar empieza a
contestar `409 destinatario-saturado`).

---

## Variables de entorno

| Variable | Para qué | Default |
| :--- | :--- | :--- |
| `TP_COLA_URLS` | La *seed list*: las URLs de los nodos de cola, separadas por comas. Con un solo elemento funciona igual. **Obligatoria** | vacío → no arranca |
| `TP_COLA_TOKEN` | El token de **consumidor**, para el header `X-Cola-Token` | vacío → sólo anda si la cola arrancó sin token |
| `TP_COLA_CONSUMIDOR` | El **`host:puerto` gRPC de la réplica**, el mismo string que el balanceador usa como `destino`. **Obligatoria y sin default** | vacío → no arranca |
| `TP_COLA_HILOS` | Consumidores dentro de este worker | `2` |
| `TP_COLA_ESPERA` | Segundos que dura el long-polling (techo 30) | `20` |
| `TP_COLA_ADMIN` | Puerto del panel | `9091` |
| `TP_COLA_URL` | Alias viejo de `TP_COLA_URLS`, vale como lista de un elemento | vacío |
| `TP_REDIS_URL` | Base compartida. Sin ella, las tareas de personas responden `UNAVAILABLE` | vacío |
| `HOST_NAME` · `CASA` · `TP_LOGS` · `TP_GRACE` | Como en el servidor gRPC | ídem |

## El panel

```bash
curl -s localhost:9101/estado    # contadores, reparto por operación, qué tiene en vuelo
curl -s localhost:9101/salud     # lo que consulta el HEALTHCHECK
```

```json
{"consumidor":"100.91.134.43:8080","hilos":2,"colaSana":true,
 "master":"http://100.101.15.93:8086",
 "tomadas":76,"resueltas":38,"fallidas":38,"descartadas":0,"saturadas":0,"perdidas":0,
 "porOperacion":{"CrearPersona":22,"Salud":15,"ListarPersonas":14,"Echo":12,"Identidad":12},
 "enVuelo":[],"ultimaTarea":"3947f03b · Identidad · OK"}
```

`master` es contra cuál de los nodos está hablando ahora. Es lo primero que hay que mirar
cuando el clúster cambió de master y hay que saber si el worker se mudó o se quedó pegado.

---

## Decisiones

**La dirección de la cola es una lista, no una URL.** Sólo el master atiende, y el master
cambia: el worker arranca con la seed list, descubre quién manda por `/health`, lo cachea, y
cuando el nodo viejo le contesta `421` actualiza el caché y reintenta ahí — sin reiniciar el
proceso. En régimen normal el descubrimiento no cuesta nada: `/health` se consulta al
arrancar y cuando algo se rompe, nunca antes de cada operación.

**Un `404` al tomar no es "no hay trabajo".** El `204` sí. Es la distinción más cara del
worker: tratar el `404` como cola vacía —que es lo que hacía la versión anterior, escrita
antes de que el contrato estuviera publicado— deja al worker girando en silencio contra la
ruta equivocada, sano para el healthcheck y sin resolver nada. La falla que no se ve es peor
que la que se ve, así que ahora sale por el log y por el backoff.

**El `consumidor` no tiene valor por defecto.** El contrato pide el `host:puerto` gRPC de la
réplica, el mismo string que el balanceador usa como `destino`; con cualquier otro, la
réplica figura sana y sin consumir nada y nadie se entera. Un default inventado hace que el
worker arranque igual y mienta, así que preferimos que no arranque.

**El GET es de long-polling y no polling corto.** La cola implementa `tomar(consumidor,
espera)`: el que pide trabajo se queda esperando y lo despiertan apenas hay algo. Con
polling cada 200 ms serían cientos de requests por minuto sin trabajo, y cada pedido se
vería con hasta 200 ms de retraso. Con una request que espera 20 s no hay ni ruido ni
latencia.

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

## Preguntas abiertas para el equipo de la cola

El módulo que pasaron (`implementacion colas.txt`) define la estructura de datos; lo que
falta es la capa HTTP (`servidor.py`). Todo esto está **asumido** en `CONTRATO.md §8` y
aislado en `ClienteCola.java`: confirmarlo o corregirlo no toca nada más.

**Las que cambian código:**

1. **Las rutas.** ¿Confirman `GET`/`POST`/`DELETE` contra la **misma** URL? ¿Cuál es el path?
2. **Los parámetros del GET.** ¿Se llaman `consumidor` y `espera`? ¿`espera` va en segundos?
3. **Cola vacía.** ¿`204` sin cuerpo? El worker también acepta `200` vacío y `404`, pero
   conviene fijar uno.
4. **El POST del resultado.** ¿El cuerpo es `{id, estado, contenido, atendidoPor, app}`?
   ¿Qué código devuelve al aceptar, y cuál cuando la tarea ya no existe (asumimos `409`)?
5. **¿Exponen la devolución** (`devolver_pedido`)? Si no, el worker que se apaga no puede
   soltar lo que no empezó y hay que esperar a que venza la reserva.
6. **Casing del contenido.** Nosotros mandamos los nombres del `.proto` (`servido_por`).
   ¿El balanceador los reenvía tal cual al cliente, o espera camelCase?
7. **Autenticación**, si la hay. Va al `.env` de cada casa, no al repo.

**Las que definen el comportamiento del sistema:**

8. **¿Cuánto dura la reserva?** De eso depende cuánto puede tardar una tarea nuestra antes de
   que la reasignen — y si el presupuesto (`quedaMs`) alcanza para un alta con la base lenta.
9. **¿El `intento` que nos mandan lo podemos usar para decidir?** Por ejemplo, no repetir una
   escritura que ya falló dos veces.
10. **¿Qué operaciones van a encolar?** Hoy implementamos las cinco del contrato. Si va a
    haber otras, hace falta agregarlas al catálogo de §8.4.
11. **¿La cola es alcanzable desde las casas** (Tailscale / túnel) o sólo desde la red de
    ustedes?
12. **¿Nos dan un entorno de prueba** antes del día de la demo? Con un `curl` de ejemplo del
    GET y del POST alcanza para cerrar las siete primeras.
13. **El consumidor fantasma — la más importante de todas.** Cuando un worker se apaga con
    sus `GET` colgados, del lado de ustedes esos consumidores siguen esperando, **uno por
    hilo**. Si les entregan un pedido, la escritura de la respuesta va a fallar.
    **¿Devuelven el pedido al frente cuando la escritura falla**, o queda en vuelo hasta que
    vence la reserva? Lo medimos: un `Echo` tardó **11 segundos** con un fantasma y **más de
    20** con dos. Y lo que de verdad importa: un `POST /personas` que caiga en un fantasma
    **se falla con `DEADLINE_EXCEEDED` sin haberse ejecutado**, porque las escrituras no se
    reencolan. Un alta válida le vuelve al cliente como error sólo porque alguien apagó un
    worker. Del lado del worker no hay nada que hacer.

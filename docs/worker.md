# El worker de cola

Un servicio que **toma un pedido de la cola, lo resuelve y devuelve la respuesta**. Es el
mismo servicio del contrato alcanzado por otro camino: donde el servidor gRPC atiende una
conexión, el worker va a buscar trabajo.

El contrato de la cola está en [`CONTRATO.md §8`](../CONTRATO.md); la arquitectura, en los
diagramas [8 y 9](diagramas.md).

```
                   ┌──────────── GET  ?consumidor&espera ────────────┐
   balanceador ──▶ │  COLA  │                                        ▼
                   │        │ ◀──────────── POST resultado ──── WORKER ──▶ Redis
   balanceador ◀── └────────┘                                        └──▶ bitácora
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

```bash
export CASA=casa-justino
export TP_COLA_URL=<la-url-que-den>

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

```bash
./mvnw -q package

# 1) la cola falsa (dejar corriendo en una pestaña)
MSYS_NO_PATHCONV=1 java -cp target/app-java.jar ar.edu.unlu.sdypp.planb.ColaFalsa 8000 /tareas

# 2) un worker (otra pestaña)
TP_COLA_URL=http://localhost:8000/tareas HOST_NAME=casa-justino-worker-1 CASA=casa-justino \
  TP_LOGS=logs/worker-1 \
  java -cp target/app-java.jar ar.edu.unlu.sdypp.worker.Worker

# 3) publicar tareas y recolectar las respuestas, como haría el balanceador
MSYS_NO_PATHCONV=1 java -cp target/app-java.jar ar.edu.unlu.sdypp.planb.ColaFalsa \
  publicar http://localhost:8000/tareas 40 "balanceador@casa-justino"
```

Para ver el reparto, levantar un segundo worker con otro `HOST_NAME` y otro
`TP_COLA_ADMIN`.

> `MSYS_NO_PATHCONV=1` es para Git Bash en Windows: sin eso, MSYS convierte el argumento
> `/tareas` en una ruta de Windows y el servidor no arranca. Es el mismo problema que ya
> tiene documentado el volumen de la bitácora en `deploy.sh`.

---

## Variables de entorno

| Variable | Para qué | Default |
| :--- | :--- | :--- |
| `TP_COLA_URL` | URL del servicio de cola. La misma para el GET y el POST. **Obligatoria** | vacío → no arranca |
| `TP_COLA_CONSUMIDOR` | Cómo se identifica ante la cola | `java@$HOST_NAME` |
| `TP_COLA_HILOS` | Consumidores dentro de este worker | `2` |
| `TP_COLA_ESPERA` | Segundos que dura el long-polling del GET | `20` |
| `TP_COLA_ADMIN` | Puerto del panel | `9091` |
| `TP_REDIS_URL` | Base compartida. Sin ella, las tareas de personas responden `UNAVAILABLE` | vacío |
| `HOST_NAME` · `CASA` · `TP_LOGS` · `TP_GRACE` | Como en el servidor gRPC | ídem |

## El panel

```bash
curl -s localhost:9101/estado    # contadores, reparto por operación, qué tiene en vuelo
curl -s localhost:9101/salud     # lo que consulta el HEALTHCHECK
```

```json
{"consumidor":"java@casa-justino-worker-1","hilos":2,"colaSana":true,
 "tomadas":76,"resueltas":38,"fallidas":38,"descartadas":0,"perdidas":0,
 "porOperacion":{"CrearPersona":22,"Salud":15,"ListarPersonas":14,"Echo":12,"Identidad":12},
 "enVuelo":[],"ultimaTarea":"3947f03b · Identidad · OK"}
```

---

## Decisiones

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
| ⬜ | `docker stop` → drenado → soltar lo no empezado | Falta: necesita Docker corriendo |
| ⬜ | Reserva vencida → reasignación al otro worker | Falta: hay que matar un worker con una tarea en la mano |
| ⬜ | Alta real contra Redis y lectura desde la otra casa | Falta: necesita Docker corriendo |

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

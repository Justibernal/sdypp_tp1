# Contrato de servicio — App Java ↔ App Python

**v2.2 · gRPC + Protobuf** — lo que las dos implementaciones tienen que responder **igual** para
ser intercambiables detrás del balanceador. La **§8 es la v3.0**: el mismo servicio alcanzado
por la cola de tareas, ya cerrada contra la especificación que publicó el equipo que la
implementa.

El esquema formal está en **[`contrato.proto`](src/main/proto/contrato.proto)**. Acá va lo que el `.proto` no
puede expresar: validación, orden de los chequeos y semántica de los errores.

> Cada punto está decidido. Si algo hay que cambiar, se cambia acá y sube la versión — no se
> resuelve por chat ni se asume distinto de cada lado.

---

## 1. Reglas generales

| Regla | Valor |
| :--- | :--- |
| Transporte | **gRPC sobre HTTP/2** · Protobuf 3 |
| Paquete / servicio | `sdypp` / `sdypp.Servicio` |
| Canal | **Inseguro** (sin TLS): el cifrado lo pone Tailscale por debajo |
| Puerto | Primer argumento de CLI; si no, `PORT`; default **8080** |
| Identidad | `HOST_NAME` (instancia) y `CASA` (nodo), por variable de entorno |
| Zona horaria | **`America/Argentina/Buenos_Aires`** en las dos implementaciones |

---

## 2. Los cinco RPC

### `Identidad` → `Instancia`

| Campo | Tipo | Detalle |
| :--- | :--- | :--- |
| `app` | string | `"python"` o `"java"`. Permite ver qué implementación atendió. |
| `lenguaje` | string | Texto libre, informativo. |
| `equipo` | repeated Integrante | `nombre`, `apellido`, `legajo` por persona. |
| `version` | int32 | Lo que cambia en cada deploy. |
| `mensaje` | string | Texto plano. |
| `host` | string | Valor de `HOST_NAME`. |
| `arrancado` | string | ISO-8601 con offset, **precisión de segundos, sin fracción**. |

### `Salud` → `EstadoSalud`

Devuelve `status: SANO`, `app` y `version`. `status` es un **enumerado**, no un string: con texto
libre una implementación manda `"ok"` y la otra `"OK"`. El valor `0` (`ESTADO_NO_ESPECIFICADO`) es
obligatorio en proto3 y es el que llega si el campo no se manda, así que **no** significa sano.

> **Además hay que exponer `grpc.health.v1.Health`**, el estándar: es lo que consultan el
> `HEALTHCHECK` del contenedor y el balanceador. `Salud` lleva `app` y `version`, que el estándar
> no tiene. Se implementan los dos.

### `Echo` → `PongRespuesta`

Devuelve `pong` con el valor recibido, más `servido_por` y `version`.
Si `ping` viene vacío: `INVALID_ARGUMENT` · `se requiere el campo ping`.

### `ListarPersonas` → `ListaPersonas`

`servido_por` y las personas **ordenadas por `id` ascendente**. Sin personas devuelve lista vacía,
no un error. Si la base no responde: `UNAVAILABLE` · `base de datos no disponible`.

### `CrearPersona` → `RespuestaPersona`

| Situación | Código | Mensaje |
| :--- | :--- | :--- |
| Alta correcta | `OK` | — |
| `nombre` vacío o sólo espacios | `INVALID_ARGUMENT` | `se requieren los campos nombre y legajo` |
| `legajo` fuera de `1 … 2147483647` | `INVALID_ARGUMENT` | `legajo fuera de rango` |
| `nombre` de más de 120 caracteres | `INVALID_ARGUMENT` | `nombre inválido` |
| `legajo` ya registrado | `ALREADY_EXISTS` | `el legajo ya está registrado` |
| La base no responde | `UNAVAILABLE` | `base de datos no disponible` |

---

## 3. Validación: reglas y orden

Las dos apps validan **igual** y en **este orden**. El orden es contrato: ante un mensaje con dos
problemas a la vez, las dos tienen que devolver el mismo error y no cada una el que detectó primero.

1. **`nombre` presente**, después del trim. En proto3 esto cubre también el caso de no mandarlo.
2. **`legajo` en `1 … 2147483647`.** El `0` cae acá, y en proto3 el `0` es también lo que llega
   cuando el campo no se manda: los dos casos dan el mismo error, que es lo que se quiere.
3. **`nombre` de 1 a 120 caracteres**, sobre el valor ya trimeado.
4. **`legajo` no registrado**, o `ALREADY_EXISTS`.

| Regla | Decisión |
| :--- | :--- |
| Espacios en `nombre` | **Trim** de los extremos. Los internos se preservan: no se colapsan. |
| Largo | Caracteres sobre el valor trimeado. |
| `nombre` duplicado | **Permitido.** Lo único único es el `legajo`. |

### Matriz de verificación

| `NuevaPersona` | Esperado |
| :--- | :--- |
| `nombre:"Ada Lovelace" legajo:100200` | `OK` |
| `nombre:"  Ada Lovelace  " legajo:100201` | `OK`, guardado como `"Ada Lovelace"` |
| `nombre:"Ada  Lovelace" legajo:100202` | `OK`, los espacios internos se conservan |
| `nombre:"Ada" legajo:100200` (repetido) | `ALREADY_EXISTS` |
| `nombre:""` / `nombre:"   "` / sin `nombre` | `INVALID_ARGUMENT` campos requeridos |
| sin `legajo` / `legajo:0` / `legajo:-5` | `INVALID_ARGUMENT` legajo fuera de rango |
| `nombre:<121 caracteres>` | `INVALID_ARGUMENT` nombre inválido |
| `nombre:<120 caracteres>` | `OK` |
| cualquiera, con Redis caído | `UNAVAILABLE` |

> **El tipado eliminó trece casos borde** que la v1.3 validaba a mano: legajo como string, decimal,
> booleano, en notación exponencial, mayor a int32, cuerpo no-JSON, cuerpo vacío… Con Protobuf el
> stub del cliente los rechaza antes de salir a la red. Es el argumento más fuerte a favor de gRPC
> y va en el informe.

---

## 4. El estado: Redis en un contenedor

Las dos apps leen y escriben sobre **la misma base**. El estado sale de las instancias, que quedan
*stateless* y por lo tanto reemplazables entre sí.

| Clave | Tipo | Contenido |
| :--- | :--- | :--- |
| `personas:seq` | string | Contador. `INCR` devuelve el `id` de la próxima persona. |
| `persona:<id>` | hash | `nombre`, `legajo` |
| `personas:index` | sorted set | miembro y score `<id>` — mantiene el orden de listado |
| `legajo:<legajo>` | string | `<id>`. Se crea de forma atómica para detectar duplicados. |

**El esquema de claves es contrato tanto como el `.proto`.** Si una implementación guardara la
persona bajo otra clave, las dos apps escribirían en la misma base sin encontrar lo del otro.

El alta va en **un script Lua**: chequeo de duplicado, `INCR`, `HSET`, `ZADD` y `SET` en una sola
operación atómica. Entre comprobar que el legajo no está y escribirlo, otra réplica puede colarse
con el mismo; y entre pedir el `id` y usarlo, otra puede pedir el mismo.

La URL llega por **`TP_REDIS_URL`**. Lleva la contraseña adentro: **no se versiona**, va en el
`.env` de cada nodo.

---

## 5. Bitácora a disco

Una línea por RPC atendido, en el disco local del nodo — no en la base.

```
2026-09-08T14:03:22-03:00 | java@casa-agustina | CrearPersona | OK | id=7
```

| Campo | Contenido |
| :--- | :--- |
| 1 | Timestamp ISO-8601 con offset, precisión de segundos |
| 2 | `<app>@<CASA>` |
| 3 | Nombre del RPC |
| 4 | Código gRPC de la respuesta |
| 5 | `id=<n>` si involucra una persona; `-` si no |

**Un archivo por réplica** (`bitacora-<HOST_NAME>.log`): dos réplicas en el mismo nodo escribiendo
el mismo archivo no se pueden distinguir después, y distinguirlas es lo que la auditoría de la
Etapa 2 tiene que demostrar. El objetivo es cruzarlo con el log del balanceador: él registra a
quién derivó, el nodo registra qué hizo.

---

## 6. Contenedores

| Regla | Valor |
| :--- | :--- |
| Puerto dentro del contenedor | `8080` |
| Variables obligatorias | `HOST_NAME`, `CASA`, `TP_REDIS_URL` |
| Usuario | **no-root** |
| `HEALTHCHECK` | contra `grpc.health.v1.Health`, no con `curl` (no hay HTTP que consultar) |
| Apagado | `SIGTERM` → `NOT_SERVING` → `server.stop(grace)`, sin morir de golpe |

El plazo de gracia del contenedor tiene que ser **mayor que el `grace` del servidor**, o llega el
`SIGKILL` en medio del drenado y el graceful shutdown no sirve de nada.

---

## 7. Requisitos para el equipo Plataforma

Condiciones sin las cuales el balanceador no puede reenviar tráfico.

1. **Tiene que hablar HTTP/2.** gRPC no viaja sobre HTTP/1.1: un proxy que lee la request y la
   reenvía con una librería HTTP/1.1 **no funciona**. Dos salidas:
   - **Proxy TCP (L4):** reenviar bytes sin entender el protocolo. Camino corto, pero pierde la
     capacidad de ver qué RPC pasó — y con eso se cae el requisito de loguear a quién derivó cada
     operación.
   - **Proxy gRPC real:** entender HTTP/2 y multiplexar streams. Es bastante más que las "menos de
     cien líneas" que el enunciado estima.
2. **El health check llama a `grpc.health.v1.Health`**, no hace un GET.
3. **Una conexión gRPC es persistente y multiplexada.** No hay una conexión por request: el cliente
   abre un canal y lo reusa. Si el balanceador reparte **por conexión**, un cliente queda pegado a
   una réplica para siempre y el reparto no se ve en la demo.
4. **La IP del cliente y el id de correlación viajan como metadata gRPC**, no como cabeceras HTTP:
   `x-forwarded-for` y `x-request-id` en minúscula, que es como gRPC normaliza las claves.
5. **ngrok:** el túnel HTTP del plan free no sirve para gRPC sin TLS end-to-end. Hay que exponer el
   balanceador por el **túnel TCP**.
6. **El verificador del equipo cruzado necesita un cliente gRPC.** Ya no puede ser un `curl` en un
   `while`: hay que darles los stubs o un binario.

---

## 8. La cola de tareas — v3.0

> **Estado: cerrado.** El equipo de colas publicó su especificación
> ([`contrato-worker.md` v1](https://github.com/SDyPPTpGrupal/sdypp_balanceador/blob/feature/desacople/docs/contrato-worker.md))
> y la App Java está implementada contra ella. Lo que sigue **no la repite**: dice qué toca
> de *nuestro* lado y qué decisiones nos impone. La fuente de verdad del transporte es el
> documento de ellos; la de lo que respondemos, el
> [contrato público](https://github.com/SDyPPTpGrupal/sdypp_balanceador/blob/feature/desacople/docs/contrato-publico.md).
>
> Las siete preguntas abiertas de la v2.3 quedaron contestadas, y **casi ninguna como la
> habíamos supuesto**. La lista de lo que cambió está en [`docs/worker.md`](../docs/worker.md).

El mismo servicio, por un segundo camino: en vez de atender una conexión gRPC, un **worker**
toma el pedido de una cola, lo resuelve y devuelve la respuesta. El cliente deja de esperar
contra una conexión abierta, y el que resuelve deja de necesitar ser alcanzable.

### 8.1 · Las dos colas

| Cola | Quién publica | Quién consume |
| :--- | :--- | :--- |
| `pedidos` | El balanceador | **Los workers**, compitiendo: el que está libre toma el próximo |
| `respuestas` | Los workers | El balanceador, y sólo el que es su destinatario |

Dos colas y no una bidireccional: en `pedidos` hay N consumidores compitiendo por el mismo
elemento; en `respuestas` cada elemento tiene **un** destinatario y nadie más lo puede tomar.

### 8.2 · El endpoint del worker

Cuatro rutas, todas `POST` salvo la de descubrimiento, y todas con la credencial en
`X-Cola-Token` salvo `/health`:

| | Qué hace |
| :--- | :--- |
| `POST /pedidos/tomar` `{consumidor, espera}` | Toma el próximo pedido y lo **reserva**. `200` con el pedido · `204` si no hubo nada |
| `POST /respuestas` `{id, estado, contenido, atendidoPor, app}` | Devuelve la tarea resuelta. `202` aceptada · `409` con dos significados |
| `POST /pedidos/devolver` `{id, consumidor}` | Suelta un pedido sin atenderlo (al apagarse). `200` · `409 no-estaba-en-vuelo` |
| `GET /health` | Quién es el master, y qué contrato habla. **Sin token** |

El `tomar` es de **long-polling** —techo de 30 s— y el `espera` va en el cuerpo, no en la
query.

**Hay un master y hay que encontrarlo.** `TP_COLA_URLS` no es una lista de réplicas
equivalentes sino una **seed list** de un clúster donde sólo el master atiende. La razón no
es arbitraria: `tomar` **no es una lectura**, es una mutación —reserva el pedido y lo saca
del pool—, así que repartirla entre nodos entregaría el mismo pedido dos veces. El worker
descubre el master con `/health`, lo cachea, y le habla sólo a él; un `421 no-soy-master`
trae la dirección nueva y se reintenta una vez ahí.

Esto **invirtió una decisión nuestra**: la v2.4 rotaba entre nodos para repartir carga, que
es lo natural si son réplicas. Contra este clúster eso sería un generador de `421`. Rotar
estaba bien razonado y era incorrecto — la diferencia no estaba en el razonamiento sino en
un hecho del otro sistema que no teníamos.

**Los dos `409` de `/respuestas` no significan lo mismo**, y se distinguen por el cuerpo y no
por el status:

| Cuerpo | Qué pasó | Qué hacemos |
| :--- | :--- | :--- |
| `{"resultado": "desconocido"}` | Otro ya la contestó; llegamos tarde | Descartar. **No es un error del worker** |
| `{"resultado": "destinatario-saturado"}` | El balanceador no está recolectando | Reintentar con backoff, y contarlo aparte |

Contarlos juntos escondería el segundo, que es el único de los dos que indica un problema.

**El `consumidor` no es un nombre libre.** Tiene que ser el `host:puerto` **gRPC** de la
réplica, porque es el string con el que el balanceador la tiene registrada y el que publica
en su `/health` como "réplicas que están consumiendo". Un identificador inventado deja a la
réplica figurando como sana y sin consumir nada. Por eso `TP_COLA_CONSUMIDOR` **no tiene
default**: es preferible que el worker no arranque a que arranque con un nombre que nadie va
a poder cruzar.

**El major del contrato se verifica al arrancar.** `/health` declara `contrato`; si el major
no es el que implementa el worker, **no arranca** (salida 3). Un major distinto significa que
algún campo cambió de nombre o que un código cambió de significado: seguir sería responder
con campos que ya no quieren decir lo mismo, en silencio.

### 8.3 · El sobre

**Pedido** — lo que el worker recibe:

```json
{"id": "a3f9", "operacion": "POST /personas",
 "parametros": {"nombre": "Ada Lovelace", "legajo": 100200},
 "idempotente": false, "cliente": "10.0.0.7", "quedaMs": 4200, "intento": 0}
```

**Respuesta** — lo que el worker devuelve:

```json
{"id": "a3f9", "estado": "OK", "atendidoPor": "100.101.15.93:8111", "app": "java",
 "contenido": {"servidoPor": "java",
               "persona": {"id": 7, "nombre": "Ada Lovelace", "legajo": 100200}}}
```

| Campo | Contenido |
| :--- | :--- |
| `id` | El de la tarea, tal cual vino. Es lo que la cola usa para cerrarla |
| `estado` | **Nombre del código gRPC**: `OK`, `INVALID_ARGUMENT`, `ALREADY_EXISTS`, `UNAVAILABLE`, `UNIMPLEMENTED`, `DEADLINE_EXCEEDED` |
| `contenido` | El mensaje del contrato en JSON, **en camelCase** (`servidoPor`, no `servido_por`) |
| `atendidoPor` | El mismo `host:puerto` gRPC que el worker manda como `consumidor` |
| `app` | `"java"` o `"python"`, como en el resto del contrato |

**El `contenido` va en camelCase y no con los nombres del `.proto`.** Es la otra suposición
que se cayó, y no es cosmética: este objeto es **exactamente** lo que el balanceador le
publica al cliente final, sin tocar nada — el contrato público lo documenta campo por campo.
Con los nombres del `.proto`, la misma operación se vería distinta según qué réplica la
atendió, que es justo lo que las dos implementaciones existen para evitar. El `.proto` sigue
mandando puertas adentro; la conversión pasa en la frontera (`Ejecutor`), en un solo lugar.

El `destinatario`, los `intentos` y la espera los completa **la cola** con lo que guardó del
pedido: el worker no tiene por qué saber quién lo pidió, y si se lo preguntáramos podría
mentir.

Un fallo va con el código en `estado` y el motivo en el contenido, que es la misma forma que
usa la cola cuando falla por su cuenta:

```json
{"id": "a3f9", "estado": "INVALID_ARGUMENT",
 "contenido": {"error": "legajo fuera de rango", "app": "java"}}
```

### 8.4 · Las operaciones

`operacion` nombra la request HTTP que originó el pedido, porque es lo que recibe el
balanceador. La equivalencia con los RPC es la misma de la v1.3 → v2.0:

| `operacion` | RPC | `parametros` | Idempotente |
| :--- | :--- | :--- | :--- |
| `GET /` | `Identidad` | — | sí |
| `POST /echo` | `Echo` | `ping` | sí |
| `GET /personas` | `ListarPersonas` | — | sí |
| `POST /personas` | `CrearPersona` | `nombre`, `legajo` | **no** |
| `GET /health` | `Salud` | — | sí · *fuera del catálogo de la cola* |

Las cuatro primeras son el catálogo que encola el balanceador. `GET /health` **no está** en
su tabla —el `/health` público lo contesta él, y es sobre el balanceador y la cola, no sobre
nuestra app— pero el worker lo sigue entendiendo: sobrar una operación no le cuesta nada a
nadie, y responder `UNIMPLEMENTED` a algo que alguna vez nos manden sí.

Una operación que no está en la tabla se responde `UNIMPLEMENTED`, nunca con una excepción:
el pedido llegó bien y el problema es el catálogo, así que reintentarlo no cambiaría nada.

**`legajo` llega siempre como entero**: el balanceador lo normaliza antes de encolar, y un
cuerpo con un legajo que no es entero lo rechaza él con `400`. La conversión de §8.5 se queda
igual de todos modos — es nuestra red, no la de ellos, y el día que cambien de criterio el
worker no se entera por un `500`.

### 8.5 · Los trece casos borde vuelven

La §3 celebra que el tipado de Protobuf eliminó trece casos borde que la v1.3 validaba a
mano. Por la cola viaja JSON: **vuelven todos**, porque ya no hay stub del cliente que los
rechace antes de salir a la red. La conversión los resuelve, y la regla es que **no inventa
errores nuevos** — los mapea a los que el contrato ya define:

| Llega | Se convierte en | Resultado |
| :--- | :--- | :--- |
| `"legajo": "100200"` | `100200` | Se acepta: un formulario manda todo como texto |
| `"legajo": 100.5` · `1e5` · `true` · `"abc"` · ausente | `0` | `INVALID_ARGUMENT · legajo fuera de rango` |
| `"legajo": 3000000000` | `0` | `INVALID_ARGUMENT · legajo fuera de rango` |
| `"nombre": 42` · `{}` · `[]` | ausente | `INVALID_ARGUMENT · se requieren los campos nombre y legajo` |

El orden de los chequeos de la §3 se respeta igual: ante un pedido con dos problemas a la
vez, el worker devuelve el mismo error que devolvería el servidor gRPC.

### 8.6 · Reintentos y entrega

| Situación | Quién la resuelve | Cómo |
| :--- | :--- | :--- |
| El worker se muere con la tarea en la mano | La cola | Vence la reserva. **Idempotente:** vuelve al frente. **Escritura:** `DEADLINE_EXCEEDED` |
| El worker se apaga ordenadamente | El worker | Termina la que está resolviendo y **suelta** (`DELETE`) la que no empezó |
| El `POST` de la respuesta falla | El worker | Reintenta **la entrega**, nunca la ejecución: la tarea ya se ejecutó |
| La respuesta llega segunda | La cola | La descarta (`409`). Gana la primera |
| La cola no responde | El worker | Backoff exponencial hasta 15 s. **No** se declara enfermo por eso |

**Una escritura no se reencola.** Al vencer una reserva, la cola sabe que el worker no
contestó, pero no si llegó a ejecutar: repetir una lectura no cuesta nada, repetir un alta
puede crear la persona dos veces. Un `DEADLINE_EXCEEDED` honesto es mejor que un duplicado
silencioso.

### 8.7 · Bitácora: el sexto campo

La línea del worker lleva los cinco campos de la §5 sin tocar, y **un sexto**:

```
2026-09-08T14:03:22-03:00 | java@casa-justino | CrearPersona | OK | id=7 | tarea=a3f9
```

`tarea=<id>` es el **id de correlación** que el contrato no tiene y que la auditoría
necesita: cruzar dos bitácoras por timestamp exige que los relojes de dos casas coincidan, y
el propio enunciado dice que los relojes mienten. La cola nos da un id que identifica la
operación de punta a punta; anotarlo cuesta un campo. Es la mejora nº2 que le levantamos al
enunciado, resuelta.

---

## 9. Versiones

| | Cambios |
| :--- | :--- |
| 1.0 – 1.3 | Contrato sobre HTTP/JSON: ocho divergencias resueltas, `equipo` estructurado, validación de personas y matriz de casos borde. |
| **2.0** | **Cambio incompatible: HTTP/JSON → gRPC sobre HTTP/2 con Protobuf.** Códigos HTTP → códigos gRPC. Trece casos borde desaparecen por el tipado. Se agregan health estándar, contenedores y los requisitos de §7. |
| 2.1 | Salen el RPC `Lenta`, el checksum y el rate limiting: el grupo decidió no usarlos. |
| **2.2** | Un mensaje de pedido propio por método (`IdentidadPedido`, `SaludPedido`, `ListarPersonasPedido`) en vez de uno compartido. `EstadoSalud.status` pasa de string a **enumerado**. |
| **2.3** *(propuesta)* | **Segundo camino al mismo servicio: la cola de tareas (§8).** Sobre de pedido y de respuesta, catálogo de operaciones, códigos gRPC como `estado`, la regla de reintento por idempotencia, y la conversión de JSON a los tipos del contrato — que reabre los trece casos borde que la 2.0 había cerrado. La bitácora suma un sexto campo opcional, `tarea=<id>`, que es el id de correlación que faltaba. |
| **2.4** *(propuesta)* | La cola pasa a ser **varios nodos**: `TP_COLA_URLS` acepta una lista y el worker la rota, con la respuesta volviendo a la réplica que entregó el pedido. Se agrega la **credencial** (`TP_COLA_TOKEN`), con el header y el prefijo configurables porque son especificación del otro equipo. Un nodo caído deja de contar como "cola caída", y un `401` deja de confundirse con uno. |
| **3.0** | **La §8 deja de ser propuesta: se implementa el `contrato-worker.md v1` publicado.** Cambia el transporte entero (rutas `POST /pedidos/tomar` · `/respuestas` · `/pedidos/devolver`, credencial en `X-Cola-Token`), la seed list pasa de réplicas rotativas a **clúster master/slave con descubrimiento por `/health` y redirect `421`**, los dos `409` se distinguen por el cuerpo, el `contenido` pasa a **camelCase**, el `consumidor` pasa a ser el `host:puerto` gRPC de la réplica, `quedaMs` se usa como timeout y el major del contrato se verifica al arrancar. Es mayor porque **nada de esto es compatible con la 2.4**: un worker viejo contra este clúster no toma un solo pedido. |

# Plan de la demo — equipo Java

Qué tiene que pasar el día de la entrega, qué necesitamos del equipo Plataforma y qué hace
cada integrante.

Los comandos concretos no están acá: están en **[`manana.md`](manana.md)**.

---

## Cómo funciona el sistema, en cuatro pasos

```
cliente  ──►  BALANCEADOR  ──►  RÉPLICA  ──►  REDIS  ──►  respuesta
              (elige una       (Java o        (el estado
               sana)            Python)        compartido)
```

1. El cliente le habla a **una sola URL**. No sabe ni le importa cuántas réplicas hay.
2. El **balanceador** elige una réplica **sana** y le reenvía la petición.
3. La **réplica** atiende el RPC. Si toca personas, lee o escribe en **Redis**.
4. Cada pieza deja **una línea de log**: el balanceador anota a quién derivó, la réplica
   anota qué hizo. Cruzando los dos archivos se reconstruye una operación puntual.

Las réplicas son *stateless*: no guardan nada propio. Por eso una puede atender el alta y
otra la lectura siguiente, y por eso matar una no pierde datos.

Los diagramas de todo esto están en **[`diagramas.md`](diagramas.md)**.

---

## Lo que hay que pedirle al equipo Plataforma

Sin estos tres datos no nos podemos enchufar a su balanceador. **Conviene pedirlos apenas
empiece la clase, no en el medio de la demo.**

| # | Qué | Para qué |
| :--- | :--- | :--- |
| 1 | **URL pública del balanceador** (`host:puerto`) | Es contra lo que corre el verificador |
| 2 | **Cómo se le agregan backends** — la firma exacta del pedido | El `deploy.sh` la necesita para conmutar |
| 3 | **`TP_REDIS_URL`** de la base compartida | Sin eso los RPC de personas responden `UNAVAILABLE` |

## Lo que hay que avisarles

Tres cosas sobre nuestras réplicas. Son las que más probablemente fallen:

- Hablan **gRPC sobre HTTP/2**. Un proxy que parsea HTTP/1.1 **no funciona**: o reenvía bytes
  (nivel 4) o habla HTTP/2 de verdad (nivel 7).
- El health check es **`grpc.health.v1.Health`**, no un `GET /health`.
- Si reparten **por conexión**, un cliente gRPC queda pegado a una réplica y el reparto no se
  ve. Está medido: 100 % a una sola réplica con canal compartido.

---

## El problema a resolver: que su balanceador nos alcance

Nuestras réplicas corren en la máquina de cada uno, en puertos locales. **Desde otra casa eso
no se alcanza.** Hay tres caminos, de mejor a peor:

### Opción A · Tailscale — es lo que pide el enunciado

Las máquinas entran al mismo tailnet y el balanceador nos alcanza por nombre, sin abrir
puertos al mundo.

```bash
tailscale status      # confirmar que estamos en el tailnet del grupo
tailscale ip -4       # la dirección que hay que darles
```

En Linux hay que abrir los puertos sólo hacia el tailnet:

```bash
sudo ufw allow in on tailscale0 to any port 8111 proto tcp
sudo ufw allow in on tailscale0 to any port 8112 proto tcp
sudo ufw allow in on tailscale0 to any port 8121 proto tcp
sudo ufw allow in on tailscale0 to any port 8122 proto tcp
```

### Opción B · Túnel TCP

Si Tailscale no sale. **Tiene que ser túnel TCP, no HTTP**: el túnel HTTP del plan gratuito
de ngrok no sirve para gRPC sin TLS de punta a punta.

```bash
ngrok tcp 8121
```

**Limitación:** el plan gratuito da **un solo túnel a la vez** y nosotros tenemos dos
réplicas. O se paga, o se expone una sola y se pierde parte del reparto.

### Opción C · Les pasamos la imagen y la corren ellos

La más confiable si la red no sale. Nuestra aplicación es una imagen de Docker:

```bash
docker save sdypp-app-java:local | gzip > app-java.tar.gz    # ~90 MB
```

Del otro lado:

```bash
gunzip -c app-java.tar.gz | docker load
docker run -d --name java-1 -p 8121:8080 \
  -e HOST_NAME=java-1 -e CASA=casa-plataforma \
  -e TP_REDIS_URL=<la-url-de-la-base> sdypp-app-java:local
```

**Lo que se pierde y hay que decirlo en voz alta:** las réplicas ya no están en nuestra casa,
así que no se prueba la red entre casas ni la latencia real. Pero el pool **sí** queda mixto
Java + Python detrás de un solo balanceador, que es el punto de la Etapa 2.

---

## Árbol de decisión

```
¿El equipo Plataforma tiene el balanceador andando?
│
├── SÍ ──► ¿nos alcanza desde su máquina?
│          ├── SÍ (Tailscale o túnel) ──► demo completa; nuestro conmutador queda apagado
│          └── NO ──────────────────────► Opción C: corren ellos las réplicas Java
│
└── NO ───► PLAN B: nuestro propio conmutador
```

**En los tres casos nuestro código es exactamente el mismo.** Lo único que cambia son dos
variables de entorno:

```bash
export URL=<la-url-del-balanceador>          # por defecto: localhost:8080
export CONMUTADOR_ADMIN=<su-endpoint>        # por defecto: http://localhost:9090/backends
```

El `deploy.sh` tiene la conmutación aislada en una única función justo para esto.

---

## Sobre el Plan B

Es el plan B que el enunciado permite declarar, y **hay que declararlo, no disimularlo**. Lo
que se pierde:

- Las "casas" pasan a ser contenedores en una sola máquina: no se prueba la red entre casas
  ni la latencia real entre ellas.
- El pool queda sólo con réplicas Java, sin mezclar con Python.
- El balanceador es el nuestro, no el del equipo Plataforma.

Lo que **sí** se demuestra igual: el reparto con round-robin, la expulsión por health check,
el estado compartido, la auditoría cruzando bitácoras, el deploy sin downtime, el deploy que
se aborta solo y el rollback.

---

## Reparto de roles

| Rol | Qué hace |
| :--- | :--- |
| **Terminal** | Corre los comandos de `manana.md`. Es el único que escribe |
| **Relato** | Explica qué se está viendo y por qué. No toca el teclado |
| **Bitácora** | Tiene proyectado `docker logs -f` y va señalando las líneas que aparecen |

El verificador lo corre **otro equipo, desde otra máquina** — nadie corrige su propio examen.
A ellos hay que darles la URL del balanceador y el `.jar`, o decirles que usen `grpcurl`:
nuestras réplicas exponen *reflection*, así que pueden llamarnos sin tener el `.proto`.

---

## Guion de la exposición

| Momento | Qué se muestra | Comando |
| :--- | :--- | :--- |
| 1 | El reparto entre réplicas | `manana.md` · A |
| 2 | El estado compartido: alta en una, lectura en otra | `manana.md` · B |
| 3 | La auditoría cruzando las dos bitácoras | `manana.md` · C |
| 4 | Matar una réplica: sale de rotación, los datos siguen | `manana.md` · D |
| 5 | Deploy sin downtime con el loop corriendo | `manana.md` · E |
| 6 | Rollback en un comando | `manana.md` · F |
| 7 | Deploy de una versión rota: aborta solo | `manana.md` · G |

---

## Los tres aportes propios

Los tres salieron de romper cosas midiendo, no de leer documentación. Están explicados en el
`README.md`.

1. **El formato ISO de Java omite los segundos cuando valen cero.** Una de cada sesenta
   líneas de bitácora habría salido con otro formato, y el cruce con el log del balanceador
   se rompe justo en esa.
2. **El pool de conexiones a Redis reparte conexiones muertas.** Si la base se cae y vuelve,
   la réplica queda respondiendo `UNAVAILABLE` para siempre: hay que reiniciarla a mano.
3. **Nuestro propio balanceador se cayó solo bajo carga: 59,2 % de fallos**, sin que se
   cayera ninguna réplica. Logueaba sincronizado a disco en cada conexión y declaraba muerto
   un backend con un solo timeout vencido. Corregido: 59,2 % → 0,7 %.

---

## Las mejoras al enunciado

El enunciado pide al menos tres. Están en
**[`mejoras-al-enunciado.md`](mejoras-al-enunciado.md)**, con el detalle y la evidencia. En una
línea cada una, para decirlas de memoria:

1. **"Menos de cien líneas" presupone HTTP/1.1.** Con gRPC hay que elegir entre L4 y L7, y el
   enunciado no obliga a declarar cuál ni qué se pierde. Nosotros lo medimos: con canal
   compartido, un balanceador L4 manda **el 100 % a una sola réplica**.
2. **La auditoría se pide cruzando timestamps, y el propio enunciado admite que los relojes de
   las casas difieren.** Falta un **id de correlación** en el contrato de logging: convierte el
   cruce en un `grep` exacto y lo saca de la dependencia del reloj.
3. **Pide health checks pero no define los umbrales.** Ni cuántos fallos expulsan, ni cuántos
   aciertos reincorporan, ni por qué no son el mismo número. A nosotros un solo timeout vencido
   nos dejó el pool vacío **con las dos réplicas sanas**.
4. **La tarea elimina tres puntos únicos de falla e introduce uno nuevo —la base compartida— y lo
   menciona recién al final, entre los Picantes.**

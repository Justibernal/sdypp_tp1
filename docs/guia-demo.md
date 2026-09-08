# Guía de la demo — equipo Java

Qué hace cada uno, en qué orden, y qué comando corre. Pensada para tener abierta durante la
clase.

---

## Cómo funciona, en cuatro pasos

```
cliente  ──►  BALANCEADOR  ──►  RÉPLICA  ──►  REDIS  ──►  respuesta
              (elige una       (Java o        (el estado
               sana)            Python)        compartido)
```

1. El cliente le habla a **una sola URL**. No sabe ni le importa cuántas réplicas hay.
2. El **balanceador** elige una réplica **sana** (round-robin) y le reenvía la request.
3. La **réplica** atiende el RPC. Si toca personas, lee o escribe en **Redis**.
4. Cada pieza deja **una línea de log**: el balanceador anota a quién derivó, la réplica
   anota qué hizo. Cruzándolas se reconstruye una operación puntual.

Las réplicas son *stateless*: no guardan nada propio. Por eso una puede atender el alta y
otra la lectura siguiente, y por eso matar una no pierde datos.

---

## Antes de la clase — cada uno en su casa

```bash
docker info                       # 1. Docker andando
cd ~/sdypp_servJava
./mvnw -q package                 # 2. compila (la primera vez baja dependencias)
docker build -t sdypp-app-java:local .
tailscale status                  # 3. si el balanceador es remoto
```

**Verificación cruzada entre nosotros:** que otro del equipo corra
`java -cp target/app-java.jar ar.edu.unlu.sdypp.Cliente TU-HOST:8121 identidad` y le
responda. Si eso anda, estás en el pool.

---

## Lo que hay que pedirle al equipo Plataforma

Sin estos tres datos no nos podemos enchufar. **Pedirlos apenas empiece la clase, no en el
medio de la demo.**

| # | Qué | Para qué |
| :--- | :--- | :--- |
| 1 | **URL pública del balanceador** (`host:puerto`) | Es contra lo que corre el verificador |
| 2 | **Cómo se le agregan backends** — la firma exacta del pedido | El `deploy.sh` la necesita para conmutar |
| 3 | **`TP_REDIS_URL`** de la base compartida | Sin eso los RPC de personas dan `UNAVAILABLE` |

Y hay que **avisarles tres cosas** sobre nuestras réplicas:

- Hablan **gRPC sobre HTTP/2**. Un proxy que parsea HTTP/1.1 **no funciona**: o reenvían
  bytes (L4) o hablan HTTP/2 de verdad (L7).
- El health check es **`grpc.health.v1.Health`**, no un `GET /health`.
- Si reparten **por conexión**, un cliente gRPC queda pegado a una réplica y el reparto no
  se ve en la demo. Está medido: 100 % a una sola réplica con canal compartido.

---

## El problema real: que su balanceador NOS ALCANCE

Nuestras réplicas corren en la Mac de casa, en `localhost:8121` y `localhost:8122`. Desde
otra casa **eso no se alcanza**. Hay que resolverlo, y hay tres caminos, de mejor a peor:

### Opción A — Tailscale (la que pide el enunciado)

Las dos máquinas entran al mismo tailnet y su balanceador nos alcanza por nombre, sin abrir
puertos al mundo.

```bash
tailscale status                    # confirmar que estamos en el tailnet del grupo
tailscale ip -4                     # la IP que hay que darle
sudo ufw allow in on tailscale0 to any port 8121 proto tcp   # sólo en Linux
```

Le pasamos `casa-justino:8121` y `casa-justino:8122`.

### Opción B — túnel TCP

Si Tailscale no sale. **Tiene que ser túnel TCP, no HTTP**: el túnel HTTP del plan free de
ngrok no sirve para gRPC sin TLS end-to-end.

```bash
ngrok tcp 8121
```

**Limitación:** el plan free da **un solo túnel a la vez**, y nosotros tenemos dos réplicas.
O se paga, o se expone una sola —y ahí perdemos parte del reparto.

### Opción C — le pasamos la imagen y las corre él

La más confiable si la red no sale. Nuestra app es una imagen Docker: se la mandamos y él
levanta dos réplicas Java al lado de las Python.

```bash
docker save sdypp-app-java:local | gzip > app-java.tar.gz     # ~90 MB comprimido
# él, del otro lado:
gunzip -c app-java.tar.gz | docker load
docker run -d --name java-1 -p 8121:8080 \
  -e HOST_NAME=java-1 -e CASA=casa-plataforma \
  -e TP_REDIS_URL=<la-url-de-la-base> sdypp-app-java:local
```

**Lo que se pierde y hay que decirlo:** las réplicas ya no están en nuestra casa, así que no
se prueba la red entre casas ni la latencia real. Pero el pool **sí** queda mixto Java+Python
detrás de un solo balanceador, que es el punto de la Etapa 2.

---

## Árbol de decisión para mañana

```
¿Tomás tiene el balanceador andando?
├── SÍ ──► ¿nos alcanza desde su máquina?
│          ├── SÍ (Tailscale o túnel) ──► DEMO COMPLETA. Nuestro conmutador queda apagado.
│          └── NO ──► Opción C: le pasamos la imagen, corre él las réplicas Java.
└── NO ───► PLAN B: nuestro conmutador. Se dice en voz alta qué se pierde.
```

En los tres casos **nuestro código es el mismo**. Lo único que cambia:

```bash
URL=<la-que-den>                        # en vez de localhost:8080
CONMUTADOR_ADMIN=<su-endpoint>          # en vez de http://localhost:9090/backends
```

---

## Lo que les damos a ellos

```
casa-justino   →  <host-tailscale>:8121   (Java v7)
casa-justino   →  <host-tailscale>:8122   (Java v7)
```

Levantar las réplicas apuntando a la Redis del grupo:

```bash
TP_REDIS_URL='redis://:PASS@casa-nomico:6379/0' ./deploy/deploy.sh desplegar
./deploy/deploy.sh estado          # confirmar que las dos quedaron healthy
```

---

## Guion de la demo

### Momento 0 — dejar esto proyectado

```bash
docker logs -f sdypp-java-green-1          # la bitácora en vivo
```

### Momento 1 — el reparto (Etapa 2)

Lo corre **el equipo verificador, desde otra casa**:

```bash
java -cp app-java.jar ar.edu.unlu.sdypp.Verificador carga <URL-BALANCEADOR> 200 8 conexion 20
```

Se espera: 200 OK, y el reparto alternando entre réplicas Java y Python.

### Momento 2 — el estado compartido

```bash
java -cp app-java.jar ar.edu.unlu.sdypp.Verificador secuencia <URL-BALANCEADOR> 700100
```

El alta la atiende una app y la lectura la otra — y el dato está. Ahí se ve el
`servidoPor` distinto en las dos respuestas.

### Momento 3 — la auditoría

Se elige **un alta concreta** del verificador y se la rastrea en dos archivos:

```bash
grep "id=7" logs/green/bitacora-*.log        # qué hizo la réplica
grep "22:03:2"  logs/conmutador/bitacora-*.log   # a quién derivó el balanceador
```

Dos archivos, dos máquinas, una historia.

### Momento 4 — matar una réplica

```bash
docker stop sdypp-java-green-2
```

El loop sigue, la réplica sale de rotación a los 3 chequeos fallidos (≈9 s), y **las personas
guardadas siguen estando**.

### Momento 5 — el deploy sin downtime (Etapa 1)

Con el loop del verificador corriendo:

```bash
# subir Config.VERSION y después:
CONMUTADOR_ADMIN=<endpoint-de-plataforma> ./deploy/deploy.sh desplegar
```

Se ve la versión cambiar en las respuestas, sin requests perdidas.

### Momento 6 — el deploy que se aborta solo

Se rompe la app a propósito, se despliega, y **el `deploy.sh` no conmuta**: baja las nuevas,
sale con código 1, y el usuario nunca ve la versión rota.

### Momento 7 — el rollback

```bash
./deploy/deploy.sh rollback
```

Vuelve a la versión anterior, que seguía viva al lado. Un comando.

---

## Plan B — si el balanceador no llega

Es el plan B que el enunciado permite declarar. **Hay que decirlo en voz alta y explicar qué
se pierde:** las "casas" pasan a ser contenedores en una sola máquina, así que no se prueba
la red entre casas ni la latencia real, y el balanceador es nuestro, no el de Plataforma.

Todo lo demás se demuestra igual:

```bash
# 1. levantar réplicas y base
./deploy/deploy.sh desplegar

# 2. levantar nuestro conmutador apuntando a las que sirven
CASA=casa-justino TP_LOGS=logs/conmutador \
  java -cp target/app-java.jar ar.edu.unlu.sdypp.planb.Conmutador \
       8080 9090 localhost:8121,localhost:8122

# 3. de acá en adelante, el guion de arriba con URL = localhost:8080
export CONMUTADOR_ADMIN=http://localhost:9090/backends
```

Bajar todo al final:

```bash
pkill -f planb.Conmutador
./deploy/deploy.sh bajar
```

---

## Si algo falla

| Síntoma | Mirar |
| :--- | :--- |
| `UNAVAILABLE` en personas | `TP_REDIS_URL`: ¿está seteada? ¿resuelve el host de la base? |
| El contenedor no llega a `healthy` | `docker logs sdypp-java-green-1 \| tail -30` |
| El balanceador no nos ve | ¿Habla HTTP/2 o L4? ¿Chequea `grpc.health.v1.Health`? |
| El reparto no se ve | ¿El verificador usa canal compartido? Probar con `conexion` |
| `docker stop` tarda 10 s | Normal: es el drenado. Con `grace` = 8 s debería salir antes |

```bash
docker ps -a
docker inspect --format '{{.State.Health.Status}}' sdypp-java-green-1
java -cp target/app-java.jar ar.edu.unlu.sdypp.Cliente localhost:8121 salud
curl -s localhost:9090/estado     # sólo con el conmutador Plan B
```

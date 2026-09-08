# Mañana en clase — de cero, comando por comando

Copiá y pegá de arriba hacia abajo. Cada bloque dice qué tenés que ver si salió bien.

**Todo el arranque tarda ~30 segundos.** Hacelo apenas llegues, no cuando te toque exponer.

---

## Paso 0 · Abrir la terminal y entrar al proyecto

```bash
cd ~/sdypp_servJava
```

> **Importante:** el proyecto **no** está en la carpeta de la materia. La ruta
> `📚 Facu/...` tiene un emoji y `java -jar` falla desde ahí. Siempre desde `~/sdypp_servJava`.

Confirmá que estás bien parado:

```bash
pwd && ls
```

Tenés que ver `pom.xml`, `Dockerfile`, `src`, `deploy`, `docs`.

---

## Paso 1 · ¿Docker está andando?

```bash
docker info > /dev/null 2>&1 && echo "Docker OK" || echo "Docker CAIDO"
```

Si dice **CAIDO**, abrí Docker Desktop y esperá ~15 s:

```bash
open -a Docker
```

Después repetí el chequeo hasta que diga OK. **Nada funciona sin esto.**

---

## Paso 2 · Compilar

```bash
./mvnw -q package
```

Tarda **~4 segundos** (la primera vez del día puede tardar más). No tiene que imprimir nada.
Confirmá que salió el jar:

```bash
ls -lh target/app-java.jar
```

Se espera: **≈ 22 MB**.

---

## Paso 3 · Levantar todo: la base y las dos réplicas

```bash
./deploy/deploy.sh desplegar
```

Tarda **~15 segundos**. Tenés que ver:

```
[  ok  ] sdypp-java-blue-1 sano y sirviendo v7
[  ok  ] sdypp-java-blue-2 sano y sirviendo v7
[  ok  ] sirviendo blue (v7)

  color activo: blue
  sdypp-java-blue-1   running   healthy   8111
  sdypp-java-blue-2   running   healthy   8112
```

Ese comando levantó Redis, construyó la imagen y arrancó **dos réplicas** — no hace falta
nada más.

---

## Paso 4 · Levantar el balanceador

**Sólo si vamos con el Plan B** (nuestro conmutador). Si el balanceador lo pone Plataforma,
saltá al Paso 5 y usá la URL que te den.

Abrí una **segunda pestaña de terminal** y dejá esto corriendo a la vista:

```bash
cd ~/sdypp_servJava
CASA=casa-justino TP_LOGS=logs/conmutador \
  java -cp target/app-java.jar ar.edu.unlu.sdypp.planb.Conmutador \
       8080 9090 localhost:8111,localhost:8112
```

Tenés que ver:

```
Conmutador (PLAN B) escuchando en 0.0.0.0:8080 · admin en :9090
[conmutador] backends: localhost:8111(sano), localhost:8112(sano)
```

Los dos tienen que decir **(sano)**. Si dicen `(caido)`, volvé al Paso 3.

---

## Paso 5 · Probar que todo responde

Volvé a la primera pestaña.

```bash
cd ~/sdypp_servJava
alias c='java -cp target/app-java.jar ar.edu.unlu.sdypp.Cliente'
alias v='java -cp target/app-java.jar ar.edu.unlu.sdypp.Verificador'
```

> Ese `alias` vale sólo en esa pestaña. Si abrís otra, repetilo.

```bash
c localhost:8080 identidad
c localhost:8080 alta "Ada Lovelace" 100200
c localhost:8080 personas
```

Se espera: `app: "java"`, después la persona creada con `id: 1`, después la lista con esa
persona. **Si esto anda, estás listo.**

---

## Paso 6 · Dejar la bitácora proyectada

Tercera pestaña, y no la toques más:

```bash
cd ~/sdypp_servJava && docker logs -f sdypp-java-blue-1
```

Ahí se ve, en vivo, cada operación que atiende esa réplica.

---

# La demo

## A · El reparto

```bash
v carga localhost:8080 200 4 conexion 20
```

Se espera: **200 OK, 0 fallidas**, y el reparto ~50/50 entre las dos réplicas.

> **Si el reparto da 100 % a una sola**, no es un error: es que el balanceador reparte por
> conexión. Sacá el `conexion` del final para mostrar el caso contrario y explicá el hallazgo.

## B · El estado compartido

```bash
v secuencia localhost:8080 700100
```

Se espera: el alta la atiende una réplica y la lectura **la otra**, y el dato está. Eso
prueba que el estado vive en Redis y no en las instancias.

## C · La auditoría

Tomá el `id` que devolvió el paso B y buscalo en los dos logs:

```bash
grep "id=7" logs/blue/bitacora-*.log
grep "CONEXION" logs/conmutador/bitacora-conmutador.log | tail -5
```

El primero dice **qué hizo** la réplica; el segundo, **a quién derivó** el balanceador.

## D · Matar una réplica

```bash
docker stop sdypp-java-blue-2
sleep 10
v carga localhost:8080 100 4 conexion 20
```

Se espera: **100 OK**, todo a la réplica que queda. Y los datos siguen:

```bash
c localhost:8080 personas
```

Levantala de nuevo:

```bash
docker start sdypp-java-blue-2
```

## E · Deploy sin downtime

Primera pestaña — arrancá el loop **en segundo plano**:

```bash
v carga localhost:8080 3000 4 conexion 50 > /tmp/loop.txt 2>&1 &
```

Ahora subí la versión y deployá **mientras el loop corre**:

```bash
sed -i '' 's/VERSION = 7/VERSION = 8/' src/main/java/ar/edu/unlu/sdypp/Config.java
CONMUTADOR_ADMIN=http://localhost:9090/backends ./deploy/deploy.sh desplegar
```

Cuando el loop termine:

```bash
cat /tmp/loop.txt
```

Se espera: **0 fallidas**, y en el reparto se ven **las dos versiones** — v7 antes de
conmutar, v8 después.

## F · El deploy que se aborta solo

Rompé la app a propósito:

```bash
sed -i '' 's|int puerto = Config.puerto(args);|if (true) throw new IllegalStateException("rota"); int puerto = Config.puerto(args);|' src/main/java/ar/edu/unlu/sdypp/AppJava.java
sed -i '' 's/VERSION = 8/VERSION = 9/' src/main/java/ar/edu/unlu/sdypp/Config.java
CONMUTADOR_ADMIN=http://localhost:9090/backends ./deploy/deploy.sh desplegar
echo "codigo de salida: $?"
```

Se espera: tarda ~60 s esperando el health, y después

```
[ERROR ] ABORTA: se bajan todas las réplicas ... y NO se conmuta
codigo de salida: 1
```

Y el servicio **sigue respondiendo la versión buena**:

```bash
c localhost:8080 identidad | grep version
```

**Dejá la app como estaba:**

```bash
git checkout src/main/java/ar/edu/unlu/sdypp/AppJava.java
sed -i '' 's/VERSION = 9/VERSION = 8/' src/main/java/ar/edu/unlu/sdypp/Config.java
```

## G · Rollback

```bash
CONMUTADOR_ADMIN=http://localhost:9090/backends ./deploy/deploy.sh rollback
c localhost:8080 identidad | grep version
```

Se espera: vuelve a la versión anterior, que seguía viva al lado. **Un comando.**

---

## Bajar todo al terminar

```bash
pkill -f planb.Conmutador
./deploy/deploy.sh bajar
```

---

## ¿Y "entrar al server"?

**Para nuestra parte, no hace falta entrar a ningún server.** Nuestras réplicas corren en
nuestras máquinas, en contenedores. En la Clase 1 se entraba por SSH al server de Plataforma
porque el deploy era manual; ahora el deploy es un script y la app viaja como imagen.

Si igual necesitás entrar al de ellos, en la Clase 1 era así — **pero esos datos son del
01/09 y seguro cambiaron, hay que pedirlos de nuevo**:

```bash
ssh -p <PUERTO-QUE-DEN> alumno@<HOST-QUE-DEN>
```

---

## Si algo falla

| Síntoma | Qué correr |
| :--- | :--- |
| Nada arranca | `docker info` — si falla, `open -a Docker` |
| `UNAVAILABLE` en personas | `docker ps \| grep redis` — ¿está la base? |
| Una réplica no llega a `healthy` | `docker logs sdypp-java-blue-1 \| tail -30` |
| El conmutador dice `(caido)` | `docker inspect --format '{{.State.Health.Status}}' sdypp-java-blue-1` |
| `command not found: c` | Repetí los `alias` del Paso 5 en esa pestaña |
| Todo raro | `./deploy/deploy.sh bajar` y volvé al Paso 3 |

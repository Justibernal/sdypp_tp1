# Arranque y demo — paso a paso

Guía de terminal del equipo Java. Se copia y se pega de arriba hacia abajo. Cada bloque dice
**qué tenés que ver** si salió bien.

El arranque completo tarda unos **30 segundos**. Hacelo apenas llegues, no cuando te toque
exponer.

---

# Parte 0 · Una sola vez, antes del día de la demo

Cada integrante, en su máquina:

```bash
git clone https://github.com/Justibernal/sdypp_tp1.git ~/sdypp_servJava
cd ~/sdypp_servJava
./mvnw -q package
```

Hace falta tener **Java 17 o superior** y **Docker Desktop** instalados. La primera
compilación baja las dependencias y puede tardar un par de minutos; las siguientes tardan
segundos.

> **El proyecto no va dentro de la carpeta de la materia.** Esa ruta tiene un emoji (`📚`) y
> `java -jar` falla desde ahí. Siempre en `~/sdypp_servJava`.

---

# Parte 1 · Arranque

## Paso 1 · Entrar al proyecto

```bash
cd ~/sdypp_servJava
pwd && ls
```

**Se espera:** la ruta `/Users/<usuario>/sdypp_servJava` y, entre los archivos, `pom.xml`,
`Dockerfile`, `src`, `deploy`, `docs`.

## Paso 2 · Confirmar que Docker está andando

```bash
docker info > /dev/null 2>&1 && echo "Docker OK" || echo "Docker CAIDO"
```

**Se espera:** `Docker OK`.

Si dice `Docker CAIDO`, abrilo y esperá unos quince segundos:

```bash
open -a Docker
```

Repetí el chequeo hasta que diga OK. **Nada de lo que sigue funciona sin esto.**

## Paso 3 · Compilar

```bash
./mvnw -q package
ls -lh target/app-java.jar
```

**Se espera:** el comando no imprime nada, y el jar pesa **≈ 22 MB**.

## Paso 4 · Empezar con las bitácoras limpias

Las bitácoras se acumulan entre corridas. Si quedan las de ayer, el momento **C** de la demo
(la auditoría) devuelve también líneas viejas y no se entiende nada.

```bash
rm -rf logs/blue logs/green logs/conmutador
```

## Paso 5 · Levantar la base y las réplicas

```bash
./deploy/deploy.sh desplegar
```

Tarda unos **15 segundos**. **Se espera**, al final:

```
[  ok  ] sdypp-java-<color>-1 sano y sirviendo vN
[  ok  ] sdypp-java-<color>-2 sano y sirviendo vN
[  ok  ] sirviendo <color> (vN)

  color activo: <color>
  sdypp-java-<color>-1   running   healthy   81x1
  sdypp-java-<color>-2   running   healthy   81x2
```

Ese único comando levantó Redis, construyó la imagen y arrancó **dos réplicas**.

> **Sobre el color:** el sistema alterna entre `blue` y `green` en cada deploy — así es como
> se despliega sin cortar el servicio. **No hace falta acordarse de cuál está activo:** el
> paso siguiente lo averigua solo.

## Paso 6 · Cargar el entorno de trabajo

```bash
source deploy/entorno.sh
```

**Se espera:**

```
  color activo : green
  réplicas     : sdypp-java-green-1 (:8121) · sdypp-java-green-2 (:8122)
  bitácoras    : /Users/.../logs/green
  URL pública  : localhost:8080
  atajos       : c <destino> <cmd>   ·   v <modo> <destino> ...
```

Esto deja listas las variables `$COLOR`, `$REPLICA_1`, `$REPLICA_2`, `$P1`, `$P2`, `$LOGS` y
`$URL`, más dos atajos: **`c`** (cliente) y **`v`** (verificador). De acá en adelante la guía
los usa.

> **Hay que repetirlo en cada pestaña nueva de la terminal**, y también después de cada
> deploy, porque el color cambia.

## Paso 7 · Levantar el balanceador

**Sólo con el Plan B**, es decir, si el balanceador no lo pone el equipo Plataforma. Si lo
ponen ellos, saltá al Paso 8 y usá la URL que te den:

```bash
export URL=<la-url-que-den>
export CONMUTADOR_ADMIN=<el-endpoint-que-den>
```

Para el Plan B, abrí una **segunda pestaña** y dejá esto corriendo a la vista:

```bash
cd ~/sdypp_servJava
source deploy/entorno.sh
CASA=casa-justino TP_LOGS=logs/conmutador \
  java -cp target/app-java.jar ar.edu.unlu.sdypp.planb.Conmutador \
       8080 9090 localhost:$P1,localhost:$P2
```

**Se espera:**

```
Conmutador (PLAN B) escuchando en 0.0.0.0:8080 · admin en :9090
[conmutador] backends: localhost:8121(sano), localhost:8122(sano)
```

Los dos backends tienen que decir **`(sano)`**. Si alguno dice `(caido)`, volvé al Paso 5.

## Paso 8 · Probar que todo responde

Volvé a la primera pestaña.

```bash
c $URL identidad
c $URL alta "Ada Lovelace" 100200
c $URL personas
```

**Se espera:** `app: "java"`; después la persona creada con su `id`; después la lista
conteniéndola.

Si esto anda, **el sistema está listo**.

> Los nombres con acento se ven escapados (`Mar\303\255a`). Es normal: así imprime el formato
> de texto de Protobuf. En el mensaje que viaja por la red el acento va bien.

## Paso 9 · Dejar la bitácora proyectada

Tercera pestaña, y no se toca más:

```bash
cd ~/sdypp_servJava && source deploy/entorno.sh && docker logs -f $REPLICA_1
```

Ahí se ve, en vivo, cada operación que atiende esa réplica.

---

# Parte 2 · La demo

Siete momentos. Los A, B y C los corre **el equipo verificador desde otra máquina**; los
demás, nosotros.

## A · El reparto entre réplicas

```bash
v carga $URL 200 4 conexion 20
```

**Se espera:** `200 OK, 0 fallidas`, y el reparto repartido entre las réplicas.

> **Si da 100 % a una sola réplica, no es un error.** Significa que el balanceador reparte
> por conexión. Para mostrar el contraste, corré el mismo comando **sin** la palabra
> `conexion`: ahí el cliente reusa un solo canal y todo cae en una réplica. Es un hallazgo
> del trabajo, está explicado en `docs/diagramas.md`, diagrama 7.

## B · El estado compartido

```bash
v secuencia $URL 700100
```

**Se espera:** el alta la atiende una réplica y la lectura **la otra**, y el dato aparece
igual. Eso prueba que el estado vive en la base y no en las instancias.

**Anotá el `id` que devuelve**, hace falta para el momento C.

## C · La auditoría cruzada

Con el `id` que devolvió el paso B — reemplazá `<ID>` por ese número:

```bash
grep "id=<ID>" $LOGS/bitacora-*.log
```

**Se espera:** una línea que dice **qué hizo** la réplica y **cuál** de las dos fue:

```
logs/green/bitacora-...-green-2.log:2026-09-08T14:03:22-03:00 | java@casa-justino | CrearPersona | OK | id=7
```

Tomá el **segundo exacto** de esa línea (en el ejemplo, `14:03:22`) y buscalo en la bitácora
del balanceador:

```bash
grep "14:03:22" logs/conmutador/bitacora-conmutador.log
```

**Se espera:** las conexiones que el balanceador derivó en ese mismo segundo, cada una
diciendo **a qué réplica** la mandó.

Dos archivos, dos piezas del sistema, una sola operación reconstruida. Eso es la auditoría
que pide el enunciado.

> El balanceador registra `CONEXION` y no el nombre del RPC porque trabaja a nivel TCP: a ese
> nivel no ve qué RPC pasó. Está explicado en `diagramas.md`, diagrama 7.

## D · Matar una réplica

```bash
docker stop $REPLICA_2
sleep 10
v carga $URL 100 4 conexion 20
c $URL personas
```

**Se espera:** `100 OK, 0 fallidas`, todo dirigido a la réplica que queda, y las personas
guardadas siguen estando.

Volvé a levantarla:

```bash
docker start $REPLICA_2
```

## E · Deploy sin downtime

Arrancá el loop **en segundo plano**:

```bash
v carga $URL 3000 4 conexion 50 > /tmp/loop.txt 2>&1 &
```

Ahora, **mientras el loop corre**, subí la versión y deployá:

```bash
./deploy/version.sh 8
./deploy/deploy.sh desplegar
```

Cuando el deploy termine, esperá a que cierre el loop y mirá el resultado:

```bash
wait; cat /tmp/loop.txt
```

**Se espera:** `0 fallidas`, y en el reparto aparecen **las dos versiones** — v7 antes de
conmutar y v8 después. El servicio nunca dejó de responder.

Recargá el entorno, porque el color cambió:

```bash
source deploy/entorno.sh
```

## F · El rollback

La versión anterior nunca se bajó: sigue corriendo al lado. Volver a ella es un comando.

```bash
./deploy/deploy.sh rollback
source deploy/entorno.sh
c $URL identidad | grep -E "version|mensaje"
```

**Se espera:** `rollback hecho`, y el servicio vuelve a responder la versión anterior.

## G · El deploy que se aborta solo

Se rompe la aplicación a propósito y se intenta desplegar. Copiá el bloque entero:

```bash
python3 -c '
import pathlib
p = pathlib.Path("src/main/java/ar/edu/unlu/sdypp/AppJava.java")
s = p.read_text()
p.write_text(s.replace("int puerto = Config.puerto(args);",
    "if (true) throw new IllegalStateException(\"version rota\");\n        int puerto = Config.puerto(args);"))
print("aplicacion rota a proposito")
'
./deploy/version.sh 99
./deploy/deploy.sh desplegar; echo "codigo de salida: $?"
```

Tarda alrededor de un minuto esperando el health check. **Se espera:**

```
[ERROR ] ... no llegó a healthy en 60s (estado: unhealthy)
[ERROR ] ABORTA: se bajan todas las réplicas ... y NO se conmuta
[ERROR ] las ... nunca dejaron de servir; el usuario no vio la versión rota
codigo de salida: 1
```

Y el servicio sigue respondiendo la versión buena, sin que el usuario se entere de nada:

```bash
c $URL identidad | grep -E "version|mensaje"
```

**Dejá el código como estaba:**

```bash
git checkout src/main/java/ar/edu/unlu/sdypp/AppJava.java
./deploy/version.sh 8
```

> **Este momento va último a propósito.** Al abortar, el deploy baja las réplicas del color
> que intentó levantar, así que después queda un solo color corriendo y ya no hay a dónde
> hacer rollback. Para repetir la demo desde el principio, corré `./deploy/deploy.sh
> desplegar` una vez y volvés a tener los dos colores vivos.

---

# Parte 3 · Bajar todo al terminar

```bash
pkill -f planb.Conmutador
./deploy/deploy.sh bajar
```

**Se espera:** `[  ok  ] todo abajo`.

---

# Anexo 1 · Si algo falla

| Síntoma | Qué correr |
| :--- | :--- |
| Nada arranca | `docker info` — si falla, `open -a Docker` |
| `command not found: c` | `source deploy/entorno.sh` en esa pestaña |
| Los comandos usan el color equivocado | `source deploy/entorno.sh` — el color cambia en cada deploy |
| `UNAVAILABLE` al pedir personas | `docker ps \| grep redis` — ¿está la base levantada? |
| Una réplica no llega a `healthy` | `docker logs $REPLICA_1 \| tail -30` |
| El conmutador dice `(caido)` | `docker inspect --format '{{.State.Health.Status}}' $REPLICA_1` |
| El reparto no se ve | Agregá `conexion` al final del comando `v carga` |
| `docker stop` tarda unos segundos | Es normal: es el drenado de las peticiones en curso |
| Todo raro | `./deploy/deploy.sh bajar` y volvé al Paso 5 |

Diagnóstico general:

```bash
./deploy/deploy.sh estado
c localhost:$P1 salud
curl -s localhost:9090/estado      # sólo con el conmutador del Plan B
```

---

# Anexo 2 · Sobre "entrar al server"

**Para nuestra parte no hace falta entrar a ningún servidor.** Las réplicas corren en las
máquinas del equipo, dentro de contenedores. En la Clase 1 se entraba por SSH al servidor de
Plataforma porque el deploy era manual; ahora el deploy es un script y la aplicación viaja
como imagen de Docker.

Si hiciera falta entrar al de Plataforma, hay que pedirles la dirección y el puerto del día
— los de la Clase 1 ya no sirven:

```bash
ssh -p <PUERTO> <USUARIO>@<HOST>
```

---

# Anexo 3 · Referencia rápida de comandos

| Comando | Qué hace |
| :--- | :--- |
| `source deploy/entorno.sh` | Carga variables y atajos. **Tras cada deploy y en cada pestaña** |
| `./deploy/deploy.sh desplegar` | Construye, levanta al lado, verifica y conmuta |
| `./deploy/deploy.sh rollback` | Vuelve a la versión anterior |
| `./deploy/deploy.sh estado` | Qué color sirve y cómo está cada réplica |
| `./deploy/deploy.sh bajar` | Baja todo |
| `./deploy/version.sh [n]` | Muestra o fija la versión declarada |
| `c $URL identidad` | Quién atendió y qué versión sirve |
| `c $URL alta "<nombre>" <legajo>` | Da de alta una persona |
| `c $URL personas` | Lista lo guardado |
| `c $URL salud` / `c $URL health` | El RPC del contrato / el health estándar |
| `v carga $URL <N> <hilos> conexion <pausaMs>` | N peticiones; códigos y reparto |
| `v secuencia $URL <legajo>` | Un alta y la lectura siguiente |
| `v concurrencia $URL <legajo> <N>` | N altas del mismo legajo a la vez |

---

# Anexo 4 · Si corrés en Windows (Git Bash)

Todo el proyecto anda igual, pero hay cuatro diferencias. Las tres primeras ya están
resueltas en el código; la cuarta la tenés que poner vos.

**1 · Se usa Git Bash, no PowerShell.** Los scripts son `bash`. Abrí *Git Bash* y usá `sh
deploy/deploy.sh ...` en vez de `./deploy/deploy.sh ...`. La ruta del proyecto se escribe
`/c/Users/<usuario>/sdypp_servJava`.

**2 · El proyecto va en `C:\Users\<usuario>\sdypp_servJava`.** Fuera de la carpeta de la
materia: esa ruta tiene espacios y un emoji, y `java -jar` falla desde ahí.

**3 · Docker Desktop tiene que estar abierto** antes de cualquier cosa. No hay `open -a
Docker`: se abre desde el menú Inicio. Chequeo: `docker info > /dev/null 2>&1 && echo OK`.

**4 · `CASA` hay que pasarla siempre.** El `deploy.sh` tiene `casa-justino` como valor por
defecto. Si no la pasás, tu bitácora dice que atendió la casa de otro — y la auditoría de la
Etapa 2 es justamente demostrar qué casa atendió qué. En cada terminal, antes de todo:

```bash
export CASA=casa-agustina
```

Con eso, `deploy.sh` y el conmutador la toman solos y no hay que repetirla en cada comando.

### Arranque completo en Windows

```bash
cd /c/Users/Usuario/sdypp_servJava
export CASA=casa-agustina

sh deploy/deploy.sh desplegar
source deploy/entorno.sh

# Segunda pestaña: el balanceador del Plan B
cd /c/Users/Usuario/sdypp_servJava && export CASA=casa-agustina
source deploy/entorno.sh
java -cp target/app-java.jar ar.edu.unlu.sdypp.planb.Conmutador 8080 9090 localhost:$P1,localhost:$P2
```

Para que el `deploy.sh` conmute el balanceador de verdad (momentos E y F), la primera pestaña
necesita además:

```bash
export CONMUTADOR_ADMIN=http://localhost:9090/backends
```

### Dos detalles que muerden

| Síntoma | Por qué |
| :--- | :--- |
| `tail -2 logs/blue/*.log` da `option used in invalid context` | El `tail` de Git Bash no acepta `-2` con varios archivos. Usá `tail -n 2 <archivo>`, de a uno |
| Los acentos salen como `c�digos` | La consola de Windows no es UTF-8. Los atajos `c` y `v` de `entorno.sh` ya pasan `-Dstdout.encoding=UTF-8`; si llamás a `java` a mano, agregalo |

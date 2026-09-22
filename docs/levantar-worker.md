# Levantar un worker en tu casa

De cero a atendiendo pedidos del grupo. Se copia y se pega de arriba hacia abajo, y cada
bloque dice **qué tenés que ver** si salió bien.

Tarda unos 10 minutos la primera vez. Después, arrancar son dos comandos.

> **Por qué hacen falta dos procesos.** El worker toma pedidos de la cola, pero se presenta
> ante ella con el `host:puerto` **gRPC de tu réplica** — y el balanceador publica ese string
> en su `/health` como "réplicas que están consumiendo". Si no hay un servidor gRPC
> escuchando de verdad ahí, figurás como réplica viva en una dirección donde no hay nadie.
> Por eso se levantan los dos: la réplica gRPC y el worker.

---

## Antes de empezar

| | |
| :--- | :--- |
| Java 17 o más | `java -version` |
| Docker | Sólo si vas a usar `deploy/worker.sh`. Para el modo directo no hace falta |
| Tailscale | Con sesión en la red **del grupo**, no en una propia |
| Git Bash | En Windows. PowerShell también sirve; abajo están los dos |

**La ruta importa.** Cloná en una carpeta **sin tildes, ñ ni emojis**. Con una `ñ` en el
camino, `protoc` falla al compilar y el error no dice por qué. `~/sdypp_servJava` va bien.

Te tienen que pasar por privado dos cosas, que **no están en el repo**:

- el **token de consumidor** de la cola;
- la **URL de Redis** con su contraseña.

---

## 1 · Entrar a la tailnet del grupo

```bash
tailscale up
tailscale status
```

**Qué tenés que ver:** la lista con los nodos de todo el grupo (`mateon`, `salvador`,
`tomas`, …), no una sola línea con tu máquina.

> **El error más común.** Si `tailscale status` muestra **un solo nodo**, estás en una tailnet
> propia y no en la del grupo. No se arregla con `tailscale up`: hace falta que alguien del
> grupo te mande la invitación, y después `tailscale logout` y volver a autenticar con ese
> link.

Anotá tu IP, que la vas a usar en el paso 3:

```bash
tailscale ip -4        # algo como 100.104.61.29
```

Comprobá que llegás a la cola:

```bash
curl -s http://100.78.246.64:8085/health
```

**Qué tenés que ver:** un JSON con `"cola": "sana"` y `"contrato": "1.0"`.

---

## 2 · Bajar el repo y compilar

```bash
git clone https://github.com/Justibernal/sdypp_tp1.git ~/sdypp_servJava
cd ~/sdypp_servJava
./mvnw -q package -DskipTests
```

**Qué tenés que ver:** nada. Si no imprime errores, quedó `target/app-java.jar`.

---

## 3 · Configurar esta casa

```bash
cp deploy/.env.ejemplo .env
```

Editá `.env` y completá cuatro cosas:

```bash
CASA=casa-TUNOMBRE
TP_COLA_TOKEN=<el token de consumidor que te pasaron>
TP_COLA_HOST=<tu IP de tailscale, la del paso 1>
TP_REDIS_URL=<la URL de Redis que te pasaron>
```

`TP_COLA_URLS` ya viene con los tres nodos del clúster: no hace falta tocarla.

> **`.env` está en `.gitignore`.** El token y la contraseña de la base no van al repo.
> Si alguna vez los ves en un `git status`, algo se rompió.

### El puerto gRPC, y una trampa de Windows

El worker se presenta como `<TP_COLA_HOST>:<puerto gRPC>`. Por defecto el script usa 8111.

**En Windows, Hyper-V reserva el rango 8054–8253** y el bind falla con un
`Address already in use` aunque `netstat` no muestre nada en ese puerto. Comprobalo:

```bash
netsh interface ipv4 show excludedportrange protocol=tcp
```

Si tu puerto cae en un rango reservado, elegí uno afuera —**8300** funciona— y fijalo
explícito en el `.env`:

```bash
TP_COLA_CONSUMIDOR=<tu IP de tailscale>:8300
```

---

## 4 · Comprobar antes de levantar nada

```bash
./deploy/worker.sh diagnostico
```

**Qué tenés que ver:** cinco líneas verdes.

```
[  ok  ] nodo 100.78.246.64:8085 · rol master · contrato 1.0 · master http://100.78.246.64:8085/
[  ok  ] nodo 100.91.134.43:8085 · rol slave · contrato 1.0 · master http://100.78.246.64:8085/
[  ok  ] nodo 100.120.186.92:8085 · rol slave · contrato 1.0 · master http://100.78.246.64:8085/
[  ok  ] consumidor del worker 1: 100.104.61.29:8300
[  ok  ] base 100.101.15.93:6379 responde y acepta la contraseña
```

Si algo sale rojo, no sigas: está todo en [Si algo falla](#si-algo-falla).

---

## 5 · Levantar

### Terminal 1 — la réplica gRPC

```bash
set -a && source .env && set +a
PORT=8300 HOST_NAME=$CASA-1 java -jar target/app-java.jar
```

**Qué tenés que ver:**

```
Servidor gRPC en 0.0.0.0:8300 (PID: …) (Arrancado: …)
[personas] base compartida en redis://100.101.15.93:6379
```

El puerto tiene que ser el mismo que pusiste en `TP_COLA_CONSUMIDOR`.

### Terminal 2 — el worker

```bash
set -a && source .env && set +a
HOST_NAME=$CASA-worker-1 TP_LOGS=logs/worker-1 \
  java -cp target/app-java.jar ar.edu.unlu.sdypp.worker.Worker
```

**Qué tenés que ver:**

```
[cola] 3 nodo(s) · como 100.104.61.29:8300 · espera 20s · token en X-Cola-Token
[cola] contrato 1.0 · master http://100.78.246.64:8085 · cola-mateo
[personas] base compartida en redis://100.101.15.93:6379
[panel] http://0.0.0.0:9091/estado
```

Esa tercera línea es la importante: encontró el master solo y el contrato coincide. A partir
de ahí, una línea por cada pedido que atienda.

---

## 6 · Comprobar que estás atendiendo

### Tu panel

**Git Bash**

```bash
curl -s localhost:9091/estado
```

**PowerShell** — ojo, acá `curl` es un alias de `Invoke-WebRequest` y `curl -s` falla:

```powershell
$e = Invoke-RestMethod http://localhost:9091/estado
$e | Select-Object maestro, colaSana, tomadas, resueltas, perdidas, erroresDeCola | Format-List
```

**Qué tenés que ver:** `colaSana: True`, `maestro` con una URL, y `perdidas: 0`.

### El balanceador te tiene que listar

```bash
curl -s https://tomas.tail93cadf.ts.net/health
```

**Qué tenés que ver:** tu `host:puerto` adentro de `"replicas"`. Si no aparece,
`TP_COLA_CONSUMIDOR` no es tu IP de Tailscale.

### Un pedido de punta a punta

**Git Bash**

```bash
for i in $(seq 1 6); do curl -s https://tomas.tail93cadf.ts.net/ | grep -o '"host": "[^"]*"'; done
```

**PowerShell**

```powershell
1..6 | ForEach-Object {
  $r = Invoke-RestMethod https://tomas.tail93cadf.ts.net/ -TimeoutSec 20
  "[{0}] {1}" -f $r.Code, $r.contenido.host
}
```

**Qué tenés que ver:** tu `casa-TUNOMBRE-worker-1` en las respuestas. Si hay otras casas
levantadas, se reparten.

Y un alta real, que es la que escribe en la base compartida:

```powershell
$L = Get-Random -Minimum 890000 -Maximum 899999
$b = @{ nombre = "Ada Lovelace"; legajo = $L } | ConvertTo-Json -Compress
$r = Invoke-RestMethod https://tomas.tail93cadf.ts.net/personas -Method Post `
       -ContentType 'application/json' -Body $b -TimeoutSec 25
"[$($r.Code)] id=$($r.contenido.persona.id) servidoPor=$($r.contenido.servidoPor)"
```

**Qué tenés que ver:** `[201]` con un `id`, y `servidoPor=java`.

---

## 7 · Mirar los logs

Hay tres lugares, y cada uno sirve para algo distinto.

| Dónde | Qué tiene |
| :--- | :--- |
| `logs/worker-1/bitacora-*.log` | Una línea por tarea resuelta, con el id de correlación |
| La terminal del worker | Arranque, cambios de master, errores de red |
| `localhost:9091/estado` | Los contadores ahora mismo |

**Seguir la bitácora en vivo:**

```bash
tail -f logs/worker-1/bitacora-*.log                          # Git Bash
```
```powershell
Get-Content logs\worker-1\bitacora-*.log -Wait -Tail 20       # PowerShell
```

Cada línea tiene seis campos:

```
2026-09-22T19:27:26-03:00 | java@casa-fede | CrearPersona | OK | id=15 | tarea=aab1021b…
   timestamp                  app@casa           RPC       código  id     id de correlación
```

**Cuántas de cada código llevás:**

```bash
awk -F'|' '{gsub(/ /,"",$4); print $4}' logs/worker-1/bitacora-*.log | sort | uniq -c | sort -rn
```
```powershell
Get-Content logs\worker-1\bitacora-*.log |
  ForEach-Object { ($_ -split '\|')[3].Trim() } |
  Group-Object | Sort-Object Count -Descending | Format-Table Count, Name -AutoSize
```

> `INVALID_ARGUMENT` y `ALREADY_EXISTS` **no son errores tuyos**: son respuestas correctas
> del contrato a un legajo inválido o repetido. El panel los cuenta como `fallidas` porque
> mira el código, no la culpa. Los que sí hay que mirar son `perdidas` y `erroresDeCola`.

---

## 8 · Apagar

**Primero el worker**, después la réplica. Ctrl+C en cada terminal.

El worker deja de tomar pedidos, termina el que tiene en la mano y **devuelve a la cola** el
que no empezó, para que otra casa lo agarre en el acto. Al revés —bajando primero la
réplica— el worker queda resolviendo contra una base que todavía está, pero anunciando una
dirección gRPC muerta.

**Qué tenés que ver:**

```
[ Graceful Shutdown ] Señal recibida. No se toman más pedidos.
[ Graceful Shutdown ] Worker detenido. N tareas resueltas, M con error.
```

---

## Si algo falla

| Qué ves | Qué es |
| :--- | :--- |
| `tailscale status` muestra un solo nodo | Estás en tu propia tailnet. Pedí la invitación del grupo, `tailscale logout` y reautenticá |
| `nodo … NO responde` en el diagnóstico | Tailscale caído, o no estás en la red del grupo |
| `Address already in use` al levantar la réplica | Rango reservado de Hyper-V. Usá 8300 y actualizá `TP_COLA_CONSUMIDOR` |
| `Falta TP_COLA_CONSUMIDOR` | Falta `TP_COLA_HOST` en el `.env`. No tiene default a propósito |
| `la cola rechazó la credencial (403)` | Token equivocado, o es el del balanceador en vez del de consumidor |
| `CONTRATO INCOMPATIBLE`, sale con código 3 | El clúster subió de major. Hay que actualizar el worker, no forzarlo |
| `maestro: null` y `colaSana: false` | El clúster está en elección o caído. El worker reintenta solo con backoff: no lo reinicies |
| `maestro: null` pero el `/health` de ellos anda | Los nodos están anunciando una dirección que no se resuelve desde acá. Mirá qué dice `masterConocido` |
| Las personas dan `UNAVAILABLE` | No se cargó el `.env`, o `TP_REDIS_URL` está mal |
| No aparecés en `replicas` | `TP_COLA_CONSUMIDOR` no es tu IP de Tailscale, o la réplica gRPC no está levantada |
| `protoc` falla con `directory does not exist` | La ruta del repo tiene tildes o `ñ`. Cloná en `~/sdypp_servJava` |
| En PowerShell, `curl -s` tira un error raro | `curl` es alias de `Invoke-WebRequest`. Usá `curl.exe` o `Invoke-RestMethod` |

---

## En contenedores, con varios workers

El modo de arriba levanta un worker a mano, que es lo que conviene para la demo. Para
escalar en una casa:

```bash
./deploy/worker.sh levantar 2     # dos workers en contenedores
./deploy/worker.sh estado
./deploy/worker.sh escalar 4
./deploy/worker.sh bitacora
./deploy/worker.sh bajar
```

Cada worker se presenta como `<TP_COLA_HOST>:<8110 + n>` y necesita **una réplica gRPC
escuchando en ese puerto**, o va a figurar como réplica viva en una dirección vacía.

**Un hilo por worker, y más workers.** `TP_COLA_HILOS` queda en 1 a propósito: un worker de
N hilos es N tareas en riesgo cuando se muere, y N consumidores fantasma cuando se apaga.
Escalar con más procesos de un hilo sale más barato en las dos cosas.

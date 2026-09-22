#!/usr/bin/env bash
#
# Los workers de la cola. Se despliegan aparte de las réplicas gRPC a propósito: son otro
# servicio, se escalan por su cuenta y se reinician sin tocar lo que está sirviendo.
#
#   ./deploy/worker.sh diagnostico    ¿se alcanzan la cola y la base? (mirar esto primero)
#   ./deploy/worker.sh levantar [N]   construye y levanta N workers (por defecto 2)
#   ./deploy/worker.sh escalar N      deja exactamente N corriendo
#   ./deploy/worker.sh estado         qué workers hay y qué está haciendo cada uno
#   ./deploy/worker.sh bitacora       las últimas líneas de cada worker
#   ./deploy/worker.sh bajar          los baja a todos, ordenadamente
#
# La configuración sale de un .env en la raíz (ver deploy/.env.ejemplo), que está en
# .gitignore: la cola pide un token y la base una contraseña, y eso no va al repo.
#
# No hay blue-green acá. El deploy sin cortes del servicio gRPC existe porque hay un
# cliente esperando del otro lado de una conexión abierta; un worker no tiene a nadie
# esperándolo: se baja, la cola le reasigna lo que tenía en la mano, y el que entra empieza
# a tomar pedidos. La continuidad la da la cola, no el despliegue.
#
set -euo pipefail

cd "$(dirname "$0")/.."

# El token de la cola y la contraseña de la base NO van al repo: viven en un .env que ya
# está en .gitignore. Las variables que ya vengan del entorno ganan, para poder probar algo
# distinto sin editar el archivo.
#
#     cp deploy/.env.ejemplo .env && $EDITOR .env
#
ARCHIVO_ENV="${ARCHIVO_ENV:-.env}"
if [[ -f "$ARCHIVO_ENV" ]]; then
  set -a; . "$ARCHIVO_ENV"; set +a
fi

IMAGEN="${IMAGEN_WORKER:-sdypp-worker-java}"
RED="${RED:-sdypp}"
REDIS="${REDIS:-sdypp-redis}"
CASA="${CASA:-casa-justino}"

# Sin valor por defecto, a propósito. Antes apuntaba a un contenedor Redis de esta casa; la
# base del TP es UNA SOLA y la tiene el otro equipo. Un default que levanta una base local
# es peor que no tener default: el worker arranca, escribe, y todo parece andar — sólo que
# en una base que nadie más lee, y eso recién se nota cuando alguien busca por gRPC lo que
# se dio de alta por la cola y no está.
TP_REDIS_URL="${TP_REDIS_URL:-}"

# Lo que el contrato pide para hablar con la cola. Todo esto es especificación del otro
# equipo y se pasa tal cual al contenedor: cambiar cualquiera no obliga a reconstruir la
# imagen (ver Config.java).
#
#   TP_COLA_URLS        la seed list: las URLs de los nodos, separadas por comas. Con un
#                       solo elemento funciona igual.
#   TP_COLA_TOKEN       el token de CONSUMIDOR (no el de publicador: ese es del balanceador)
#   TP_COLA_CONSUMIDOR  el host:puerto gRPC de la réplica, el mismo string que el
#                       balanceador usa como `destino`. No se inventa: si no coincide, la
#                       réplica figura sana y sin consumir nada.
#
# TP_COLA_URL sigue valiendo como lista de un elemento, para no romper las guías viejas.
TP_COLA_URLS="${TP_COLA_URLS:-${TP_COLA_URL:-}}"
TP_COLA_TOKEN="${TP_COLA_TOKEN:-}"
TP_COLA_AUTH="${TP_COLA_AUTH:-X-Cola-Token}"
TP_COLA_ESQUEMA="${TP_COLA_ESQUEMA:-}"
TP_COLA_CONTRATO="${TP_COLA_CONTRATO:-1}"

# El consumidor identifica a la RÉPLICA, no al proceso: todos los workers de esta casa se
# presentan con el MISMO string. Dos workers son dos manos de la misma réplica, y así es
# como la cola los cuenta. Derivar uno distinto por worker anunciaría un puerto gRPC por
# worker — y si no hay un servidor escuchando en cada uno, la réplica figura viva en una
# dirección vacía, que es justo el fallo que el contrato advierte.
TP_COLA_CONSUMIDOR="${TP_COLA_CONSUMIDOR:-}"

# Un hilo por worker y espera corta: es la conclusión del consumidor fantasma
# (docs/worker.md). La ventana de un apagado es hilos × reserva, así que se escala con más
# workers de un hilo y no con un worker de N hilos.
TP_COLA_HILOS="${TP_COLA_HILOS:-1}"
TP_COLA_ESPERA="${TP_COLA_ESPERA:-8}"

# Puerto del panel de cada worker en el host: 9101, 9102, ...
PUERTO_BASE_PANEL=9100

log()   { printf '\033[1;34m[worker]\033[0m %s\n' "$*" >&2; }
ok()    { printf '\033[1;32m[  ok  ]\033[0m %s\n' "$*" >&2; }
error() { printf '\033[1;31m[ERROR ]\033[0m %s\n' "$*" >&2; }

contenedor() { echo "sdypp-worker-$1"; }

corriendo() {
  docker ps --format '{{.Names}}' | grep -c '^sdypp-worker-[0-9]\+$' || true
}

construir() {
  log "construyendo $IMAGEN"
  docker build -q -f Dockerfile.worker -t "$IMAGEN:local" . >/dev/null
  ok "imagen $IMAGEN:local"
}

levantar_uno() {  # <n>
  local n="$1" nombre puerto
  nombre="$(contenedor "$n")"
  puerto=$(( PUERTO_BASE_PANEL + n ))
  mkdir -p "logs/worker-$n"
  docker rm -f "$nombre" >/dev/null 2>&1 || true

  # MSYS_NO_PATHCONV=1: en Git Bash sobre Windows, MSYS reescribe los argumentos que
  # parecen rutas Unix y convierte el destino del volumen (/app/logs) en una ruta de
  # Windows — el worker arranca igual pero la bitácora nunca llega al disco de la casa.
  MSYS_NO_PATHCONV=1 docker run -d --name "$nombre" --network "$RED" \
    -p "$puerto:9091" \
    -e HOST_NAME="$CASA-worker-$n" -e CASA="$CASA" \
    -e TP_REDIS_URL="$TP_REDIS_URL" \
    -e TP_COLA_URLS="$TP_COLA_URLS" \
    -e TP_COLA_TOKEN="$TP_COLA_TOKEN" \
    -e TP_COLA_AUTH="$TP_COLA_AUTH" \
    -e TP_COLA_ESQUEMA="$TP_COLA_ESQUEMA" \
    -e TP_COLA_CONTRATO="$TP_COLA_CONTRATO" \
    -e TP_COLA_CONSUMIDOR="$TP_COLA_CONSUMIDOR" \
    -e TP_COLA_HILOS="$TP_COLA_HILOS" \
    -e TP_COLA_ESPERA="$TP_COLA_ESPERA" \
    -v "$PWD/logs/worker-$n:/app/logs" \
    --stop-timeout 15 "$IMAGEN:local" >/dev/null
  log "arriba $nombre · panel en :$puerto"
}

esperar_sano() {  # <n>
  local nombre esperado=0 estado
  nombre="$(contenedor "$1")"
  while (( esperado < 60 )); do
    estado="$(docker inspect --format '{{.State.Health.Status}}' "$nombre" 2>/dev/null || echo sin-contenedor)"
    [[ "$estado" == "healthy" ]] && { ok "$nombre sano"; return 0; }
    [[ "$estado" == "sin-contenedor" ]] && { error "$nombre no existe"; return 1; }
    sleep 2; esperado=$(( esperado + 2 ))
  done
  error "$nombre no llegó a healthy (estado: $estado)"
  docker logs --tail 20 "$nombre" >&2 || true
  return 1
}

# El host:puerto de cada réplica de TP_COLA_URLS, uno por línea.
destinos_cola() {
  echo "$TP_COLA_URLS" | tr ',' '\n' | sed -e 's#^[a-zA-Z]*://##' -e 's#/.*$##' -e 's#^ *##' -e 's# *$##' \
    | grep -v '^$' || true
}

# host:puerto de TP_REDIS_URL, sin la contraseña.
destino_redis() {
  echo "$TP_REDIS_URL" | sed -e 's#^redis[s]*://##' -e 's#^.*@##' -e 's#/.*$##'
}

# Qué se alcanza y qué no, ANTES de construir la imagen. La cola y la base están en la
# tailnet: si esta máquina no está adentro, el worker arranca igual y se queda reintentando
# para siempre, que es un síntoma mucho menos claro que decirlo acá.
diagnostico() {
  local algo_falla=0 destino salud rol master contrato vivos=0
  echo
  [[ -z "$TP_COLA_URLS" ]] && { error "TP_COLA_URLS vacía"; algo_falla=1; }

  # /health y no la raíz: es la ruta de descubrimiento del contrato, no lleva token, y dice
  # las tres cosas que hacen falta saber antes de levantar nada — si el nodo está vivo, si
  # hay master electo y qué contrato habla.
  for destino in $(destinos_cola); do
    salud="$(curl -s --max-time 5 "http://$destino/health" 2>/dev/null || true)"
    if [[ -z "$salud" ]]; then
      error "nodo $destino NO responde (¿Tailscale levantado en esta máquina?)"
      continue
    fi
    vivos=$(( vivos + 1 ))
    rol="$(echo "$salud"      | grep -oE '"rol" *: *"[^"]*"'            | head -1 | sed 's/.*"\([^"]*\)"$/\1/')"
    master="$(echo "$salud"   | grep -oE '"masterConocido" *: *"[^"]*"' | head -1 | sed 's/.*"\([^"]*\)"$/\1/')"
    contrato="$(echo "$salud" | grep -oE '"contrato" *: *"[^"]*"'       | head -1 | sed 's/.*"\([^"]*\)"$/\1/')"
    ok "nodo $destino · rol ${rol:-?} · contrato ${contrato:-?} · master ${master:-(en elección)}"
    if [[ -n "$contrato" && "${contrato%%.*}" != "$TP_COLA_CONTRATO" ]]; then
      error "  ese nodo habla contrato $contrato y el worker implementa el $TP_COLA_CONTRATO.x: no va a arrancar"
      algo_falla=1
    fi
  done
  if (( vivos == 0 )); then
    error "ningún nodo de cola responde"
    algo_falla=1
  fi

  if [[ -z "$TP_COLA_CONSUMIDOR" ]]; then
    error "falta TP_COLA_CONSUMIDOR: el host:puerto gRPC de esta réplica, con el que el"
    error "  balanceador la identifica. Sin eso figura como sana y sin consumir nada"
    algo_falla=1
  else
    ok "consumidor: $TP_COLA_CONSUMIDOR (todos los workers de esta casa usan el mismo)"
  fi

  if [[ -z "$TP_REDIS_URL" ]]; then
    error "TP_REDIS_URL vacía: las tareas de personas se van a responder UNAVAILABLE"
    algo_falla=1
  else
    destino="$(destino_redis)"
    # El chequeo va DESDE UN CONTENEDOR y no desde el host, porque es el contenedor el que
    # va a tener que llegar: un host con Tailscale que rutea y un Docker que no, se ve acá y
    # no cuando el worker ya está arriba respondiendo UNAVAILABLE.
    #
    # Con una base en loopback hay que traducir: dentro del contenedor, 127.0.0.1 es el
    # contenedor. Sin esto el chequeo fallaría siempre en pruebas locales y diría algo que
    # no es cierto.
    local url_desde_contenedor="${TP_REDIS_URL//127.0.0.1/host.docker.internal}"
    url_desde_contenedor="${url_desde_contenedor//localhost/host.docker.internal}"
    if docker run --rm --add-host host.docker.internal:host-gateway redis:8-alpine \
         redis-cli -u "$url_desde_contenedor" --no-auth-warning PING 2>/dev/null | grep -q PONG; then
      ok "base $destino responde y acepta la contraseña"
    else
      error "base $destino NO responde desde un contenedor, o la contraseña no es la que espera"
      algo_falla=1
    fi
  fi

  [[ -z "$TP_COLA_TOKEN" ]] && log "aviso: TP_COLA_TOKEN vacío — si la cola pide credencial, va a dar 401"
  echo
  return $algo_falla
}

levantar() {
  local cuantos="${1:-2}" n
  if [[ -z "$TP_COLA_URLS" ]]; then
    error "falta TP_COLA_URLS: las URLs de los nodos de cola, separadas por comas"
    error "  ej: TP_COLA_URLS=http://cola-1:8085,http://cola-2:8085,http://cola-3:8085"
    error "para probar sin el servicio del otro equipo, ver docs/worker.md (doble de prueba)"
    exit 2
  fi
  if [[ -z "$TP_REDIS_URL" ]]; then
    error "falta TP_REDIS_URL: es la base compartida del TP, la que tiene el otro equipo"
    error "sin ella el worker arranca, pero responde UNAVAILABLE a todo lo de personas"
    error "para forzarlo igual: TP_REDIS_URL=redis://$REDIS:6379/0 $0 levantar $cuantos"
    exit 2
  fi
  if [[ -z "$TP_COLA_CONSUMIDOR" ]]; then
    error "falta TP_COLA_CONSUMIDOR: el host:puerto gRPC de la réplica"
    error "  es el mismo string que el balanceador usa como \`destino\` en su pool"
    error "  ej: TP_COLA_CONSUMIDOR=100.104.61.29:8300"
    exit 2
  fi
  if [[ -z "$TP_COLA_TOKEN" ]]; then
    log "sin TP_COLA_TOKEN: sólo funciona si la cola arrancó sin token"
  fi

  # La red sigue existiendo aunque la base sea remota: es la que usan las réplicas gRPC, y
  # tener a los workers ahí adentro los deja hablarse por nombre en la demo.
  docker network inspect "$RED" >/dev/null 2>&1 || docker network create "$RED" >/dev/null

  # La base local se levanta SÓLO si TP_REDIS_URL apunta al alias del contenedor. Cuando la
  # base es la del otro equipo, levantar una acá al lado escribiría en una base que nadie
  # más lee — el fallo más caro de todos, porque no se ve.
  #
  # Tres casos y no dos: corriendo, parado, y no existe. Sin el caso "parado", el docker run
  # choca con el nombre ya tomado y el script muere con un error que no dice nada.
  if [[ "$TP_REDIS_URL" == *"//$REDIS:"* || "$TP_REDIS_URL" == *"@$REDIS:"* ]]; then
    if docker ps -a --format '{{.Names}}' | grep -qx "$REDIS"; then
      docker start "$REDIS" >/dev/null 2>&1 || true
    else
      log "levantando la base LOCAL ($REDIS) — no es la compartida del TP"
      docker run -d --name "$REDIS" --network "$RED" -p 6379:6379 \
        redis:8-alpine redis-server --appendonly yes >/dev/null
    fi
  fi

  construir
  log "cola: $TP_COLA_URLS"
  log "base: $(destino_redis) · como: $TP_COLA_CONSUMIDOR · casa: $CASA · workers: $cuantos × $TP_COLA_HILOS hilo(s)"
  for n in $(seq 1 "$cuantos"); do levantar_uno "$n"; done
  for n in $(seq 1 "$cuantos"); do esperar_sano "$n" || exit 1; done
  estado
}

# Escalar es toda la configuración que hace falta para repartir más trabajo: nadie asigna
# nada, el worker libre toma el próximo pedido. Por eso no hay que tocar ni la cola ni el
# balanceador para agregar uno.
escalar() {
  local objetivo="${1:?uso: escalar N}" actual n
  actual="$(corriendo)"
  if (( objetivo > actual )); then
    for n in $(seq $(( actual + 1 )) "$objetivo"); do levantar_uno "$n"; esperar_sano "$n" || exit 1; done
  elif (( objetivo < actual )); then
    for n in $(seq $(( objetivo + 1 )) "$actual"); do bajar_uno "$n"; done
  fi
  ok "$objetivo workers corriendo"
  estado
}

bajar_uno() {  # <n>
  local nombre
  nombre="$(contenedor "$1")"
  if docker ps -a --format '{{.Names}}' | grep -qx "$nombre"; then
    # docker stop manda SIGTERM: el worker deja de tomar pedidos, termina el que tiene en
    # la mano y suelta el que todavía no empezó, para que otro lo agarre en el acto.
    docker stop "$nombre" >/dev/null 2>&1 || true
    docker rm   "$nombre" >/dev/null 2>&1 || true
    log "bajado $nombre"
  fi
}

estado() {
  local nombre puerto n
  echo
  printf '  %-22s %-12s %-10s %-8s %s\n' CONTENEDOR ESTADO SALUD PANEL RESUELTAS/FALLIDAS
  for n in $(seq 1 20); do
    nombre="$(contenedor "$n")"
    docker ps -a --format '{{.Names}}' | grep -qx "$nombre" || continue
    puerto=$(( PUERTO_BASE_PANEL + n ))
    printf '  %-22s %-12s %-10s %-8s %s\n' "$nombre" \
      "$(docker inspect --format '{{.State.Status}}' "$nombre")" \
      "$(docker inspect --format '{{.State.Health.Status}}' "$nombre" 2>/dev/null || echo -)" \
      ":$puerto" \
      "$(curl -s --max-time 2 "localhost:$puerto/estado" 2>/dev/null \
         | grep -oE '"resueltas":[0-9]+,"fallidas":[0-9]+' || echo '(sin datos)')"
  done
  echo
}

bitacora() {
  local archivo
  for archivo in logs/worker-*/bitacora-*.log; do
    [[ -e "$archivo" ]] || continue
    echo "── $archivo"
    tail -n 5 "$archivo"
    echo
  done
}

bajar() {
  local n
  for n in $(seq 1 20); do bajar_uno "$n"; done
  ok "workers abajo"
}

case "${1:-}" in
  levantar)    levantar "${2:-2}" ;;
  escalar)     escalar "${2:-}" ;;
  estado)      estado ;;
  bitacora)    bitacora ;;
  diagnostico) diagnostico ;;
  bajar)       bajar ;;
  *) echo "uso: $0 {levantar [N]|escalar N|estado|bitacora|diagnostico|bajar}"; exit 2 ;;
esac

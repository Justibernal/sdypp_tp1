#!/usr/bin/env bash
#
# Los workers de la cola. Se despliegan aparte de las réplicas gRPC a propósito: son otro
# servicio, se escalan por su cuenta y se reinician sin tocar lo que está sirviendo.
#
#   ./deploy/worker.sh levantar [N]   construye y levanta N workers (por defecto 2)
#   ./deploy/worker.sh escalar N      deja exactamente N corriendo
#   ./deploy/worker.sh estado         qué workers hay y qué está haciendo cada uno
#   ./deploy/worker.sh bitacora       las últimas líneas de cada worker
#   ./deploy/worker.sh bajar          los baja a todos, ordenadamente
#
# No hay blue-green acá. El deploy sin cortes del servicio gRPC existe porque hay un
# cliente esperando del otro lado de una conexión abierta; un worker no tiene a nadie
# esperándolo: se baja, la cola le reasigna lo que tenía en la mano, y el que entra empieza
# a tomar pedidos. La continuidad la da la cola, no el despliegue.
#
set -euo pipefail

cd "$(dirname "$0")/.."

IMAGEN="${IMAGEN_WORKER:-sdypp-worker-java}"
RED="${RED:-sdypp}"
REDIS="${REDIS:-sdypp-redis}"
CASA="${CASA:-casa-justino}"
TP_REDIS_URL="${TP_REDIS_URL:-redis://$REDIS:6379/0}"

# La URL de la cola es lo único que hay que darle. Mientras el otro equipo no publique la
# suya, se apunta al doble de prueba (ver docs/worker.md).
TP_COLA_URL="${TP_COLA_URL:-}"

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
    -e TP_COLA_URL="$TP_COLA_URL" \
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

levantar() {
  local cuantos="${1:-2}" n
  if [[ -z "$TP_COLA_URL" ]]; then
    error "falta TP_COLA_URL: es la URL del servicio de cola (GET para tomar, POST para devolver)"
    error "para probar sin el servicio del otro equipo, ver docs/worker.md (doble de prueba)"
    exit 2
  fi
  docker network inspect "$RED" >/dev/null 2>&1 || docker network create "$RED" >/dev/null
  if ! docker ps --format '{{.Names}}' | grep -qx "$REDIS"; then
    log "levantando la base compartida ($REDIS)"
    docker run -d --name "$REDIS" --network "$RED" -p 6379:6379 \
      redis:8-alpine redis-server --appendonly yes >/dev/null
  fi

  construir
  log "cola: $TP_COLA_URL · casa: $CASA · workers: $cuantos"
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
  levantar) levantar "${2:-2}" ;;
  escalar)  escalar "${2:-}" ;;
  estado)   estado ;;
  bitacora) bitacora ;;
  bajar)    bajar ;;
  *) echo "uso: $0 {levantar [N]|escalar N|estado|bitacora|bajar}"; exit 2 ;;
esac

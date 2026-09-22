#!/usr/bin/env bash
#
# Prueba de punta a punta del worker de cola. Se corre entera y dice qué tiene que verse.
#
#   ./deploy/prueba-worker.sh            contra el doble de prueba (no hace falta el otro equipo)
#   TP_COLA_URLS=<url> ./deploy/prueba-worker.sh   contra el servicio de cola real
#   ./deploy/prueba-worker.sh limpiar    baja todo lo que levanta
#
# Cubre lo que el worker tiene que sostener: el alta real contra Redis, el legajo repetido,
# que lo dado de alta POR LA COLA se lea POR gRPC —un solo estado, dos caminos—, y el
# apagado ordenado con `docker stop`.
#
set -u

cd "$(dirname "$0")/.."

JAR=target/app-java.jar
RED="${RED:-sdypp}"
REDIS="${REDIS:-sdypp-redis}"
CASA="${CASA:-casa-justino}"
PUERTO_COLA="${PUERTO_COLA:-8000}"
DEST="balanceador@prueba"
LEGAJO=$((700000 + RANDOM % 90000))

log()  { printf '\033[1;34m[prueba]\033[0m %s\n' "$*"; }
ok()   { printf '\033[1;32m[  ok  ]\033[0m %s\n' "$*"; }
mal()  { printf '\033[1;31m[ FALLA]\033[0m %s\n' "$*"; FALLAS=$((FALLAS+1)); }
FALLAS=0

publicar() {
  curl -s -X POST "http://localhost:$PUERTO_COLA/publicar" \
    -H 'Content-Type: application/json' -d "$1" > /dev/null
}
respuesta() {
  curl -s "http://localhost:$PUERTO_COLA/recolectar?destinatario=balanceador%40prueba&espera=20"
}
# Comprueba que la respuesta traiga lo esperado, y la muestra.
esperar() {  # <que se espera> <patron> <respuesta>
  if echo "$3" | grep -q "$2"; then ok "$1"; else mal "$1 -> $3"; fi
}

limpiar() {
  sh deploy/worker.sh bajar > /dev/null 2>&1
  docker rm -f sdypp-java-prueba > /dev/null 2>&1
  pkill -f 'planb.ColaFalsa' > /dev/null 2>&1
  ok "todo abajo"
}

if [ "${1:-}" = "limpiar" ]; then limpiar; exit 0; fi

[ -f "$JAR" ] || { echo "falta $JAR: correr ./mvnw -q package"; exit 2; }
docker info > /dev/null 2>&1 || { echo "Docker no está corriendo"; exit 2; }
mkdir -p logs

# --- 0. base compartida -------------------------------------------------------
docker network inspect "$RED" > /dev/null 2>&1 || docker network create "$RED" > /dev/null
docker ps --format '{{.Names}}' | grep -qx "$REDIS" || \
  docker run -d --name "$REDIS" --network "$RED" -p 6379:6379 \
    redis:8-alpine redis-server --appendonly yes > /dev/null
log "redis: $(docker inspect --format '{{.State.Status}}' "$REDIS")"

# --- 1. la cola ---------------------------------------------------------------
if [ -z "${TP_COLA_URLS:-}" ]; then
  log "sin TP_COLA_URLS: se levanta el doble de prueba en :$PUERTO_COLA"
  MSYS_NO_PATHCONV=1 java -cp "$JAR" ar.edu.unlu.sdypp.planb.ColaFalsa "$PUERTO_COLA" \
    > logs/cola-falsa.log 2>&1 &
  sleep 3
  curl -s "localhost:$PUERTO_COLA/estado" > /dev/null || { mal "la cola no arrancó"; exit 1; }
  ok "cola de prueba arriba"
  # El worker corre en un contenedor: para él, el host es host.docker.internal
  export TP_COLA_URLS="http://host.docker.internal:$PUERTO_COLA"
else
  log "usando la cola real: $TP_COLA_URLS"
fi

# --- 2. una réplica gRPC, para probar el cruce entre los dos caminos ----------
# Puerto 8301 y no 8101: en Windows, Hyper-V se reserva el rango 8054-8253 y el bind falla
# (ver docs/manana.md, anexo 4, punto 5).
if ! docker ps --format '{{.Names}}' | grep -q 'sdypp-java-'; then
  docker image inspect sdypp-app-java:local > /dev/null 2>&1 || docker build -q -t sdypp-app-java:local . > /dev/null
  docker rm -f sdypp-java-prueba > /dev/null 2>&1
  docker run -d --name sdypp-java-prueba --network "$RED" -p 8301:8080 \
    -e HOST_NAME="$CASA-1" -e CASA="$CASA" -e TP_REDIS_URL="redis://$REDIS:6379/0" \
    --stop-timeout 15 sdypp-app-java:local > /dev/null
  sleep 12
fi
PUERTO_GRPC=$(docker ps --format '{{.Names}} {{.Ports}}' | grep 'sdypp-java-' | head -1 \
  | grep -oE '0.0.0.0:[0-9]+' | head -1 | cut -d: -f2)
log "réplica gRPC en :$PUERTO_GRPC"

# --- 3. los workers -----------------------------------------------------------
export CASA
# La base de ESTA prueba es el contenedor local, no la compartida del TP: la prueba crea y
# borra personas, y hacerlo contra la base del grupo le ensuciaría el listado a todos.
export TP_REDIS_URL="${TP_REDIS_URL:-redis://$REDIS:6379/0}"
# El consumidor tiene que tener forma de host:puerto, que es lo que el contrato pide. Acá no
# hay un balanceador que lo cruce con nada, así que alcanza con que sea estable y distinto
# por worker.
export TP_COLA_HOST="${TP_COLA_HOST:-127.0.0.1}"
sh deploy/worker.sh levantar 2 || { mal "no se pudieron levantar los workers"; exit 1; }

# --- 4. las pruebas -----------------------------------------------------------
echo
log "ALTA REAL por la cola (legajo $LEGAJO)"
publicar "{\"operacion\":\"POST /personas\",\"parametros\":{\"nombre\":\"Ada Lovelace\",\"legajo\":$LEGAJO},\"destinatario\":\"$DEST\",\"presupuestoMs\":60000}"
R=$(respuesta); echo "   $R"
esperar "el alta devuelve OK con el id que asignó la base" '"estado":"OK".*"persona":{"id":[0-9]' "$R"

echo
log "el MISMO legajo otra vez"
publicar "{\"operacion\":\"POST /personas\",\"parametros\":{\"nombre\":\"Ada Repetida\",\"legajo\":$LEGAJO},\"destinatario\":\"$DEST\",\"presupuestoMs\":60000}"
R=$(respuesta)
esperar "el legajo repetido da ALREADY_EXISTS" '"estado":"ALREADY_EXISTS"' "$R"

echo
log "un legajo ilegible: el JSON reabre los casos borde del contrato §3"
publicar "{\"operacion\":\"POST /personas\",\"parametros\":{\"nombre\":\"Ada\",\"legajo\":100.5},\"destinatario\":\"$DEST\",\"presupuestoMs\":60000}"
R=$(respuesta)
esperar "el legajo decimal da INVALID_ARGUMENT legajo fuera de rango" 'legajo fuera de rango' "$R"

echo
log "la lectura POR LA COLA lo encuentra"
publicar "{\"operacion\":\"GET /personas\",\"parametros\":{},\"destinatario\":\"$DEST\",\"presupuestoMs\":60000}"
R=$(respuesta)
esperar "la lista trae el legajo $LEGAJO" "\"legajo\":$LEGAJO" "$R"

echo
log "y POR gRPC también: el estado es uno solo"
R=$(java -Dstdout.encoding=UTF-8 -cp "$JAR" ar.edu.unlu.sdypp.Cliente "localhost:$PUERTO_GRPC" personas 2>/dev/null | tr '\n' ' ')
esperar "la misma persona aparece por el otro camino" "legajo: $LEGAJO" "$R"

echo
log "docker stop: SIGTERM, drenado y salida limpia"
INICIO=$(date +%s)
docker stop sdypp-worker-1 > /dev/null
log "tardó $(( $(date +%s) - INICIO )) s"
R=$(docker logs sdypp-worker-1 2>&1 | tail -3 | tr '\n' ' ')
esperar "el worker drenó y salió por el graceful shutdown" 'Worker detenido' "$R"

echo
log "con un worker abajo, el otro sigue atendiendo"
publicar "{\"operacion\":\"POST /echo\",\"parametros\":{\"ping\":\"sigo vivo\"},\"destinatario\":\"$DEST\",\"presupuestoMs\":120000}"
INICIO=$(date +%s)
# Ventana larga a propósito: el worker que se apagó dejó un GET colgado POR HILO del lado
# de la cola, y cada uno se traga la tarea una reserva entera antes de devolverla. Con 2
# hilos y 10 s de reserva, la primera tarea después de un apagado puede tardar 20 s. No es
# una falla: es el consumidor fantasma, explicado en docs/worker.md.
R=$(curl -s "http://localhost:$PUERTO_COLA/tareas/respuestas?destinatario=balanceador%40prueba&espera=90")
TARDO=$(( $(date +%s) - INICIO ))
esperar "la tarea se resolvió igual (tardó ${TARDO}s)" '"estado":"OK"' "$R"
if [ "$TARDO" -gt 5 ]; then
  log "esos ${TARDO}s son los GET fantasma del worker apagado (uno por hilo,"
  log "una reserva cada uno). Es un hallazgo medido: ver docs/worker.md"
  echo "$R" | grep -oE '"intentos":\[[^]]*\]' | sed 's/^/   /'
fi

echo
log "bitácora del worker, con el sexto campo"
tail -n 3 logs/worker-2/bitacora-*.log 2>/dev/null | sed 's/^/   /'

echo
if [ "$FALLAS" -eq 0 ]; then
  ok "TODO PASÓ"
else
  mal "$FALLAS verificaciones fallaron"
fi
echo
log "para bajar todo: ./deploy/prueba-worker.sh limpiar"
exit $(( FALLAS > 0 ))

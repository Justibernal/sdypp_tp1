#!/usr/bin/env bash
#
# Deploy blue-green de la App Java. Todo o nada.
#
#   ./deploy/deploy.sh desplegar   construye, levanta el color libre AL LADO y conmuta
#   ./deploy/deploy.sh rollback    vuelve al color anterior, que sigue corriendo
#   ./deploy/deploy.sh estado      qué color sirve y cómo está cada réplica
#   ./deploy/deploy.sh bajar       baja todo (las dos versiones y la red)
#
# La versión vieja NO se baja al conmutar: queda corriendo al lado para que volver
# atrás sea un comando y no un deploy en reversa. Se baja recién cuando entra una
# tercera versión.
#
set -euo pipefail

cd "$(dirname "$0")/.."

IMAGEN="${IMAGEN:-sdypp-app-java}"
REPLICAS="${REPLICAS:-2}"
RED="${RED:-sdypp}"
REDIS="${REDIS:-sdypp-redis}"
CASA="${CASA:-casa-justino}"
TP_REDIS_URL="${TP_REDIS_URL:-redis://$REDIS:6379/0}"

# Con qué dirección nos anunciamos al balanceador. "localhost" sólo sirve si el
# balanceador corre en esta misma máquina; si está en otra casa hay que anunciarse
# con la IP del tailnet, o va a intentar conectarse a SU propio localhost.
HOST_ANUNCIADO="${HOST_ANUNCIADO:-localhost}"

# Con qué balanceador hablamos:
#   plataforma  el del equipo Plataforma   POST {"agregar":[...], "quitar":[...]}
#   planb       nuestro conmutador         POST {"backends":[...]}
# "auto" lo deduce de la URL del admin.
BALANCEADOR="${BALANCEADOR:-auto}"

# Puertos de cada color en el host. El balanceador apunta a uno u otro conjunto.
PUERTO_BASE_blue=8110
PUERTO_BASE_green=8120

ESTADO="deploy/.estado"
SEGUNDOS_ESPERA_SANA=60

# --- utilidades ---------------------------------------------------------------

# Todo el log va a stderr: stdout queda libre para que una función pueda devolver
# un valor (el tag de la imagen) sin que se le mezclen los mensajes.
log()   { printf '\033[1;34m[deploy]\033[0m %s\n' "$*" >&2; }
ok()    { printf '\033[1;32m[  ok  ]\033[0m %s\n' "$*" >&2; }
error() { printf '\033[1;31m[ERROR ]\033[0m %s\n' "$*" >&2; }

# La versión que va a servir sale del código, no de un argumento: si se pasara a
# mano, un typo desplegaría una imagen diciendo que es otra versión y el verify
# de abajo perdería todo su sentido.
version_declarada() {
  grep -oE 'VERSION = [0-9]+' src/main/java/ar/edu/unlu/sdypp/Config.java | grep -oE '[0-9]+'
}

color_activo() { [[ -f "$ESTADO" ]] && cat "$ESTADO" || echo ""; }
otro_color()   { [[ "$(color_activo)" == "blue" ]] && echo "green" || echo "blue"; }

puerto_de() {  # <color> <n>
  local base="PUERTO_BASE_$1"
  echo $(( ${!base} + $2 ))
}

contenedor() { echo "sdypp-java-$1-$2"; }  # <color> <n>

cliente() { java -cp target/app-java.jar ar.edu.unlu.sdypp.Cliente "$@"; }

# --- pasos del pipeline -------------------------------------------------------

preparar_infra() {
  docker network inspect "$RED" >/dev/null 2>&1 || docker network create "$RED" >/dev/null
  if ! docker ps --format '{{.Names}}' | grep -qx "$REDIS"; then
    log "levantando la base compartida ($REDIS)"
    docker rm -f "$REDIS" >/dev/null 2>&1 || true
    docker run -d --name "$REDIS" --network "$RED" -p 6379:6379 \
      redis:8-alpine redis-server --appendonly yes >/dev/null
  fi
}

# BUILD: la imagen se construye dentro de Docker, así no depende del JDK de la
# máquina. El tag lleva la versión y el commit, con sufijo -sucio si hay cambios
# sin commitear: una imagen que no se puede volver a construir igual tiene que
# gritarlo desde el nombre.
construir() {
  local version tag sucio=""
  version="$(version_declarada)"
  [[ -n "$(git status --porcelain 2>/dev/null)" ]] && sucio="-sucio"
  tag="v${version}-$(git rev-parse --short HEAD 2>/dev/null || echo sincommit)${sucio}"

  log "construyendo $IMAGEN:$tag"
  docker build -q -t "$IMAGEN:$tag" -t "$IMAGEN:local" . >/dev/null
  ok "imagen $IMAGEN:$tag"
  echo "$tag"
}

# ARRIBA: las N réplicas del color libre, todas a la vez y no de a una. El deploy
# secuencial deja un rato con una sola réplica vieja en rotación: si esa se cae
# justo ahí, no queda nada sirviendo.
levantar_color() {  # <color> <tag>
  local color="$1" tag="$2" n puerto nombre
  mkdir -p "logs/$color"
  for n in $(seq 1 "$REPLICAS"); do
    nombre="$(contenedor "$color" "$n")"
    puerto="$(puerto_de "$color" "$n")"
    docker rm -f "$nombre" >/dev/null 2>&1 || true
    docker run -d --name "$nombre" --network "$RED" -p "$puerto:8080" \
      -e HOST_NAME="$CASA-$color-$n" -e CASA="$CASA" -e TP_REDIS_URL="$TP_REDIS_URL" \
      -v "$PWD/logs/$color:/app/logs" --stop-timeout 15 "$IMAGEN:$tag" >/dev/null
    log "arriba $nombre en :$puerto"
  done
}

# VERIFY: no se conforma con un "healthy". Compara la VERSIÓN contra la que se
# desplegó: un ship a medias deja el contenedor sano corriendo la versión anterior,
# y eso pasaría el health check sin problema.
verificar_color() {  # <color> <version_esperada>
  local color="$1" esperada="$2" n nombre puerto estado version esperado=0
  for n in $(seq 1 "$REPLICAS"); do
    nombre="$(contenedor "$color" "$n")"
    puerto="$(puerto_de "$color" "$n")"

    esperado=0
    while (( esperado < SEGUNDOS_ESPERA_SANA )); do
      estado="$(docker inspect --format '{{.State.Health.Status}}' "$nombre" 2>/dev/null || echo sin-contenedor)"
      [[ "$estado" == "healthy" ]] && break
      [[ "$estado" == "sin-contenedor" ]] && { error "$nombre no existe"; return 1; }
      sleep 2; esperado=$(( esperado + 2 ))
    done
    if [[ "$estado" != "healthy" ]]; then
      error "$nombre no llegó a healthy en ${SEGUNDOS_ESPERA_SANA}s (estado: $estado)"
      return 1
    fi

    version="$(cliente "localhost:$puerto" identidad 2>/dev/null | grep -oE '^version: [0-9]+' | grep -oE '[0-9]+' || true)"
    if [[ "$version" != "$esperada" ]]; then
      error "$nombre está sano pero sirve la versión '$version' y se desplegó la '$esperada'"
      return 1
    fi
    ok "$nombre sano y sirviendo v$version"
  done
  return 0
}

bajar_color() {  # <color>
  local color="$1" n nombre
  [[ -z "$color" ]] && return 0
  for n in $(seq 1 "$REPLICAS"); do
    nombre="$(contenedor "$color" "$n")"
    if docker ps -a --format '{{.Names}}' | grep -qx "$nombre"; then
      # docker stop manda SIGTERM y espera --stop-timeout: la réplica se declara
      # NOT_SERVING y drena los RPC en vuelo antes de morir.
      docker stop "$nombre" >/dev/null 2>&1 || true
      docker rm   "$nombre" >/dev/null 2>&1 || true
      log "bajada $nombre"
    fi
  done
}

# CONMUTAR: el único punto que depende del equipo Plataforma. Aislado a propósito
# en estas dos funciones: cuando definan la firma del endpoint, se cambia acá y
# nada más. Sin CONMUTADOR_ADMIN el deploy igual corre y avisa (demo local).
# Las direcciones de un color, tal como las ve el balanceador.
destinos_de() {  # <color>
  local color="$1" n
  for n in $(seq 1 "$REPLICAS"); do
    echo "$HOST_ANUNCIADO:$(puerto_de "$color" "$n")"
  done
}

# Qué dialecto habla el admin al que apuntamos.
dialecto() {
  if [[ "$BALANCEADOR" != "auto" ]]; then
    echo "$BALANCEADOR"
  elif [[ "${CONMUTADOR_ADMIN:-}" == */admin/backends ]]; then
    echo "plataforma"
  else
    echo "planb"
  fi
}

conmutar_a() {  # <color>
  local color="$1" anterior nuevos viejos lista cuerpo
  anterior="$([[ "$color" == "blue" ]] && echo green || echo blue)"
  nuevos="$(destinos_de "$color" | paste -sd, -)"
  viejos="$(destinos_de "$anterior" | paste -sd, -)"

  if [[ -z "${CONMUTADOR_ADMIN:-}" ]]; then
    log "sin CONMUTADOR_ADMIN: no hay balanceador al que avisarle"
    log "el color que sirve es $color -> $nuevos"
    echo "$color" > "$ESTADO"
    return 0
  fi

  # El cuerpo se arma con python3 y no concatenando strings: el JSON del
  # balanceador de Plataforma lleva objetos anidados y un `sed` mal puesto manda
  # una conmutación silenciosamente incompleta.
  cuerpo="$(python3 - "$(dialecto)" "$nuevos" "$viejos" <<'PY'
import json, sys
dialecto, nuevos, viejos = sys.argv[1], sys.argv[2].split(","), sys.argv[3].split(",")
if dialecto == "plataforma":
    # "app": "java" es obligatorio. Si se manda el destino como string suelto, el
    # balanceador asume "python" por defecto y el reparto de la demo va a decir que
    # atendió Python cuando atendió Java, que es justo lo que hay que mostrar.
    cuerpo = {
        "agregar": [{"destino": d, "app": "java"} for d in nuevos],
        "quitar": [{"destino": d} for d in viejos],
    }
else:
    cuerpo = {"backends": nuevos}
print(json.dumps(cuerpo))
PY
)"

  log "conmutando el balanceador ($(dialecto)) a $nuevos"
  if ! curl -fsS -X POST "$CONMUTADOR_ADMIN" \
        -H 'Content-Type: application/json' -d "$cuerpo" >/dev/null; then
    error "el balanceador no aceptó la conmutación; el color activo NO cambia"
    return 1
  fi
  ok "balanceador apuntando a $color"
  echo "$color" > "$ESTADO"
}

# --- comandos -----------------------------------------------------------------

desplegar() {
  local anterior nuevo tag version
  anterior="$(color_activo)"
  nuevo="$(otro_color)"
  version="$(version_declarada)"

  log "versión declarada: v$version · color activo: ${anterior:-ninguno} · desplegando en: $nuevo"
  preparar_infra
  tag="$(construir)"

  # Si ya había un tercer color dando vueltas de un deploy anterior, se limpia acá:
  # el color nuevo tiene que arrancar de cero, no reusar contenedores viejos.
  levantar_color "$nuevo" "$tag"

  if ! verificar_color "$nuevo" "$version"; then
    error "ABORTA: se bajan todas las réplicas $nuevo y NO se conmuta"
    error "las $anterior nunca dejaron de servir; el usuario no vio la versión rota"
    bajar_color "$nuevo"
    exit 1
  fi

  conmutar_a "$nuevo"
  ok "sirviendo $nuevo (v$version). Las $anterior quedan vivas para el rollback."
  estado
}

rollback() {
  local actual anterior
  actual="$(color_activo)"
  anterior="$(otro_color)"
  if ! docker ps --format '{{.Names}}' | grep -qx "$(contenedor "$anterior" 1)"; then
    error "no hay réplicas $anterior corriendo: no hay a dónde volver"
    exit 1
  fi
  log "volviendo a $anterior"
  conmutar_a "$anterior"
  ok "rollback hecho: sirve $anterior (las $actual siguen vivas)"
}

estado() {
  local color n nombre
  echo
  echo "  color activo: $(color_activo)"
  printf '  %-26s %-12s %-10s %s\n' CONTENEDOR ESTADO SALUD PUERTO
  for color in blue green; do
    for n in $(seq 1 "$REPLICAS"); do
      nombre="$(contenedor "$color" "$n")"
      docker ps -a --format '{{.Names}}' | grep -qx "$nombre" || continue
      printf '  %-26s %-12s %-10s %s\n' "$nombre" \
        "$(docker inspect --format '{{.State.Status}}' "$nombre")" \
        "$(docker inspect --format '{{.State.Health.Status}}' "$nombre" 2>/dev/null || echo -)" \
        "$(puerto_de "$color" "$n")"
    done
  done
  echo
}

bajar() {
  bajar_color blue; bajar_color green
  docker rm -f "$REDIS" >/dev/null 2>&1 || true
  rm -f "$ESTADO"
  ok "todo abajo"
}

case "${1:-}" in
  desplegar) desplegar ;;
  rollback)  rollback ;;
  estado)    estado ;;
  bajar)     bajar ;;
  *) echo "uso: $0 {desplegar|rollback|estado|bajar}"; exit 2 ;;
esac

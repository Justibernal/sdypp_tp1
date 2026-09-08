#!/usr/bin/env bash
#
# Se CARGA en la terminal, no se ejecuta:
#
#     source deploy/entorno.sh
#
# Averigua solo qué color está sirviendo y deja listas las variables y los atajos.
# Existe para que la guía no tenga que hardcodear "blue" ni ":8111": el color va
# alternando en cada deploy, así que un paso a paso con el color escrito a mano falla
# la mitad de las veces.

_raiz="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"

export COLOR="$(cat "$_raiz/deploy/.estado" 2>/dev/null || echo blue)"
export REPLICA_1="sdypp-java-$COLOR-1"
export REPLICA_2="sdypp-java-$COLOR-2"
export LOGS="$_raiz/logs/$COLOR"

# Puertos del color activo: blue usa 8111/8112, green 8121/8122.
if [ "$COLOR" = "blue" ]; then export P1=8111 P2=8112; else export P1=8121 P2=8122; fi

export URL="${URL:-localhost:8080}"
export CONMUTADOR_ADMIN="${CONMUTADOR_ADMIN:-http://localhost:9090/backends}"

# Atajos. Son funciones y no alias para que anden también dentro de scripts.
c() { java -cp "$_raiz/target/app-java.jar" ar.edu.unlu.sdypp.Cliente "$@"; }
v() { java -cp "$_raiz/target/app-java.jar" ar.edu.unlu.sdypp.Verificador "$@"; }

echo "  color activo : $COLOR"
echo "  réplicas     : $REPLICA_1 (:$P1) · $REPLICA_2 (:$P2)"
echo "  bitácoras    : $LOGS"
echo "  URL pública  : $URL"
echo "  atajos       : c <destino> <cmd>   ·   v <modo> <destino> ..."

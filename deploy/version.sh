#!/usr/bin/env bash
#
# Sube la versión que declara la app. Es lo que cambia en cada deploy.
#
#     ./deploy/version.sh          muestra la versión actual
#     ./deploy/version.sh 8        la fija en 8
#
# Con python3 y no con sed porque `sed -i` necesita un argumento en macOS y no lo
# acepta en Linux: el mismo comando no sirve en las dos máquinas del equipo.
set -euo pipefail
cd "$(dirname "$0")/.."
ARCHIVO=src/main/java/ar/edu/unlu/sdypp/Config.java

if [ $# -eq 0 ]; then
  grep -oE 'VERSION = [0-9]+' "$ARCHIVO"
  exit 0
fi

python3 - "$1" <<'PY'
import pathlib, re, sys
nueva = int(sys.argv[1])
p = pathlib.Path("src/main/java/ar/edu/unlu/sdypp/Config.java")
s = p.read_text()
vieja = int(re.search(r"VERSION = (\d+)", s).group(1))
s = re.sub(r"VERSION = \d+", f"VERSION = {nueva}", s)
s = re.sub(r'MENSAJE = "hola mundo java[^"]*"', f'MENSAJE = "hola mundo java v{nueva}"', s)
p.write_text(s)
print(f"  versión {vieja} -> {nueva}")
PY

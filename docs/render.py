#!/usr/bin/env python3
"""Genera docs/diagramas.html a partir de docs/diagramas.md.

Una sola fuente de verdad: el .md (que GitHub ya renderiza). El .html sirve para
verlos y proyectarlos en la defensa sin depender de GitHub ni de internet salvo por
la librería de Mermaid.
"""
import html
import pathlib
import re

RAIZ = pathlib.Path(__file__).parent
md = (RAIZ / "diagramas.md").read_text(encoding="utf-8")

partes, i = [], 0
for bloque in re.finditer(r"```mermaid\n(.*?)```", md, re.S):
    texto = md[i:bloque.start()]
    if texto.strip():
        partes.append(("texto", texto))
    partes.append(("mermaid", bloque.group(1)))
    i = bloque.end()
if md[i:].strip():
    partes.append(("texto", md[i:]))


def a_html(texto: str) -> str:
    """Markdown mínimo: encabezados, negrita, código y separadores. Alcanza para esto."""
    salida = []
    for linea in texto.split("\n"):
        cruda = linea.rstrip()
        if not cruda.strip():
            continue
        if cruda.startswith("---"):
            salida.append("<hr>")
            continue
        nivel = len(cruda) - len(cruda.lstrip("#"))
        contenido = cruda[nivel:].strip() if nivel else cruda
        contenido = html.escape(contenido)
        contenido = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", contenido)
        contenido = re.sub(r"`(.+?)`", r"<code>\1</code>", contenido)
        salida.append(f"<h{nivel}>{contenido}</h{nivel}>" if nivel else f"<p>{contenido}</p>")
    return "\n".join(salida)


cuerpo = "\n".join(
    f'<pre class="mermaid">{html.escape(c)}</pre>' if t == "mermaid" else a_html(c)
    for t, c in partes
)

(RAIZ / "diagramas.html").write_text(f"""<!doctype html>
<html lang="es"><head><meta charset="utf-8">
<title>Diagramas · App Java · SDyPP</title>
<style>
  body {{ font: 16px/1.6 -apple-system, system-ui, sans-serif; max-width: 1100px;
         margin: 0 auto; padding: 2rem; color: #1f2328; background: #fff; }}
  h1 {{ font-size: 2rem; border-bottom: 2px solid #d0d7de; padding-bottom: .4rem; }}
  h2 {{ font-size: 1.4rem; margin-top: 2.5rem; color: #0969da; }}
  hr {{ border: 0; border-top: 1px solid #d0d7de; margin: 2.5rem 0; }}
  code {{ background: #f0f1f3; padding: .1em .35em; border-radius: 4px; font-size: .9em; }}
  .mermaid {{ background: #f6f8fa; border: 1px solid #d0d7de; border-radius: 8px;
              padding: 1.2rem; margin: 1.2rem 0; text-align: center; overflow-x: auto; }}
  @media print {{ .mermaid {{ page-break-inside: avoid; }} }}
</style></head><body>
{cuerpo}
<script src="https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.min.js"></script>
<script>mermaid.initialize({{ startOnLoad: true, theme: 'default',
  flowchart: {{ curve: 'basis', useMaxWidth: true }} }});</script>
</body></html>""", encoding="utf-8")
print("docs/diagramas.html generado con", sum(1 for t, _ in partes if t == "mermaid"), "diagramas")

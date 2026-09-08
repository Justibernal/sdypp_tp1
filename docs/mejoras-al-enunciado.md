# Mejoras al enunciado — Tarea Clase 2

El enunciado pide **al menos tres mejoras**. Van cuatro, y ninguna es de redacción: las cuatro
son huecos que nos costaron tiempo o que descubrimos midiendo. Cada una dice **qué dice hoy el
enunciado**, **qué nos pasó** y **cómo lo cambiaríamos**.

---

## 1 · "Menos de cien líneas" presupone un protocolo que el enunciado nunca fija

**Qué dice hoy.** *"Son menos de cien líneas y ya saben hacer servers HTTP."* El balanceador
recibe, elige, reenvía y devuelve.

**Qué nos pasó.** La estimación sólo es cierta para HTTP/1.1 sin keep-alive: una request, una
conexión, una respuesta. Nuestro servicio habla **gRPC sobre HTTP/2**, donde el cliente abre
**un canal y lo reusa** para todos sus RPC. Ahí el balanceador tiene dos caminos, y el enunciado
no obliga a elegir ninguno:

| Camino | Cuesta | Se paga con |
| :--- | :--- | :--- |
| **Proxy TCP (nivel 4)** | Sí, cien líneas | No ve qué RPC pasó: la bitácora registra **conexiones**, no operaciones — y eso es justo lo que el enunciado pide loguear |
| **Proxy gRPC (nivel 7)** | Bastante más | Hay que hablar HTTP/2 y multiplexar streams |

Lo medimos con nuestro propio conmutador L4:

| Modo del verificador | Reparto observado |
| :--- | :--- |
| Un canal compartido, como un cliente gRPC real | **100 % a una sola réplica** |
| Un canal nuevo por request, como N clientes | **50 % / 50 % exacto** |

Un balanceador L4 decide **por conexión**. Con una conexión persistente y multiplexada, el
primer cliente que llega queda pegado a una réplica para siempre: **el reparto de la Etapa 2 no
se ve en la demo**, y no porque el balanceador esté mal.

**Cómo lo cambiaríamos.** Que el enunciado obligue a declarar **a qué nivel trabaja el
balanceador (L4 o L7) y qué se pierde con esa elección**, y que la estimación de esfuerzo sea por
nivel. La pregunta *"¿reenvían entendiendo la request o pasando bytes?"* ya está en "Preguntas
para pensar" — pero llega tarde: para cuando se la lee, el balanceador ya está escrito.

---

## 2 · La auditoría se pide por timestamp, y el propio enunciado dice que los relojes mienten

**Qué dice hoy.** En la Etapa 2: *"eligen una operación del verificador y la rastrean por los
archivos"*, cruzando el log del balanceador con el de la instancia. En los Picantes, la contracara:
*"los relojes de esas casas no están sincronizados (difieren segundos, o más)"*.

**Qué nos pasó.** El enunciado se contradice: la única herramienta que da para cruzar los logs es
el timestamp, y después admite que el timestamp no alcanza. En nuestra demo el cruce termina
siendo `grep "14:03:22"`, que **con carga real devuelve decenas de líneas del mismo segundo** y no
señala ninguna en particular. Y eso es en una sola máquina, con un solo reloj. Entre tres casas
no cierra directamente.

**Cómo lo cambiaríamos.** Que el enunciado incorpore al contrato de logging un **identificador de
correlación**: el balanceador genera un id por request, lo propaga (`x-request-id`) y lo escribe
en su línea; la instancia lo recibe y lo escribe en la suya.

```
2026-09-08T14:03:22-03:00 | balanceador@casa-mateo | CrearPersona | -> java-2 | req=8f3a12
2026-09-08T14:03:22-03:00 | java@casa-justino      | CrearPersona | OK       | req=8f3a12 id=7
```

La auditoría pasa de *"buscá el segundo y fijate cuál de estas veinte líneas es"* a un `grep`
exacto, y **deja de depender de los relojes**. Es un campo más en un formato que el enunciado ya
define — cuesta poco y arregla el ejercicio central de la Etapa 2.

---

## 3 · Pide health checks pero no define qué es estar sano ni cuándo se vuelve

**Qué dice hoy.** *"Con health checks: la instancia que muere sale de rotación."* Nada más: ni
cada cuánto se pregunta, ni cuántos fallos hacen falta para expulsar, ni qué hace falta para
reincorporar.

**Qué nos pasó.** Lo aprendimos rompiéndolo. Nuestro balanceador se cayó **bajo su propia carga,
con 59,2 % de fallos y sin que se cayera ninguna réplica**. Una de las tres causas fue
exactamente ésta: **un solo timeout vencido bastaba para declarar muerto un backend**. Con la JVM
ahogada, el health check se pasaba de deadline y el pool quedaba **vacío con las dos réplicas
sanas** — el balanceador se sacó de rotación a sí mismo.

La corrección expone la asimetría que el enunciado no menciona: **3 fallos seguidos para
expulsar, 1 acierto para reincorporar.** No es simétrico a propósito. Expulsar rápido es caro
porque achica el pool y concentra la carga en las que quedan; reincorporar rápido es barato
porque si la instancia sigue rota, el próximo chequeo la vuelve a sacar. Con eso: **59,2 % → 0,7 %
de fallos.**

**Cómo lo cambiaríamos.** Que el enunciado pida declarar los cuatro números como parte del
contrato — **intervalo, timeout, fallos para expulsar, aciertos para reincorporar** — y
**justificar por qué los dos últimos no son iguales**. Es donde está la sustancia del tema, y hoy
queda librado a que a alguien se le ocurra.

---

## 4 · Replica las apps y el balanceador, y deja la base sola

**Qué dice hoy.** *"Levantan un contenedor con una base de datos mínima (la que elijan)... Dónde
corre el contenedor y quién lo opera: lo negocian y lo cuentan."* Recién en los Picantes aparece
el problema: *"¿Y la base? Quedó una sola, en la casa de alguien: el SPOF cambió de lugar."*

**Qué nos pasó.** La tarea entera se llama *"Que no se caiga"* y se pasa tres etapas eliminando
puntos únicos de falla — pero **introduce uno nuevo en el enunciado y lo señala al final, como
curiosidad**. En nuestro caso la base corre en la casa de otro equipo: si esa casa se apaga, se
caen **las réplicas Java y las Python a la vez**, aunque las seis estén perfectas. Es un fallo
más grave que cualquiera de los que la tarea sí ejercita.

Nosotros lo cubrimos por contrato — la base caída devuelve `UNAVAILABLE` y el resto de los RPC
siguen sanos, y una réplica que la pierde **se recupera sola cuando la base vuelve**, sin
reiniciarse — pero eso lo decidimos nosotros, el enunciado no lo pide.

**Cómo lo cambiaríamos.** Que la sección de la base pida, junto con "dónde corre y quién la
opera", **tres cosas más**: qué responde el servicio con la base caída, si la réplica se recupera
sola cuando vuelve, y si el estado sobrevive a que se apague esa casa. Son tres preguntas que se
contestan en un párrafo y convierten el SPOF de curiosidad final en parte del ejercicio.

---

## Resumen

| # | Mejora | De dónde salió |
| :--- | :--- | :--- |
| 1 | Obligar a declarar el nivel del balanceador (L4/L7) y qué se pierde | Medido: 100 % a una réplica con canal compartido |
| 2 | Id de correlación en el contrato de logging, en vez de cruzar por reloj | El propio enunciado admite que los relojes difieren |
| 3 | Declarar y justificar los umbrales del health check | Nuestro balanceador se autodestruyó con las réplicas sanas |
| 4 | Tratar la base compartida como el SPOF que es, desde el principio | La tarea elimina SPOFs y crea uno sin decirlo |

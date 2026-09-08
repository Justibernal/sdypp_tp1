# Diagramas — App Java · Tarea Clase 2

Cinco diagramas: la arquitectura de cada etapa, el flujo del deploy y las dos secuencias
que explican por qué el sistema aguanta. Lo que está implementado y medido va en línea
llena; lo propuesto, punteado.

---

## 1 · Arquitectura — Etapa 1: un conmutador, dos colores

Termina la pelea por el puerto: una sola URL pública, y detrás dos versiones conviviendo.

![diagram](./diagramas-1.png)

**Lo que importa:** el conmutador tiene **dos planos separados**. El de datos reenvía bytes;
el de control escucha aparte, en `127.0.0.1`. Esa separación es lo que permite cambiar de
destino **sin reiniciar** — el deploy le habla al control mientras el de datos sigue
atendiendo.

---

## 2 · Arquitectura — Etapa 2: pool mixto y health checks

El conmutador ya no apunta a un backend: conoce una lista, rota entre ella, y saca de
rotación a la que deja de responder.

![diagram](./diagramas-2.png)

**El estado no vive en las réplicas.** Por eso una puede atender el alta y otra la lectura
siguiente, y por eso la que se muere no se lleva nada consigo. El SPOF se mudó: ahora está
en Redis.

---

## 3 · Arquitectura — Etapa 3: replicar al balanceador (propuesta)

![diagram](./diagramas-3.png)

Replicar el balanceador no cierra el problema, lo **corre de lugar**: aparece el split-brain
y la pregunta de quién balancea a los balanceadores. En algún punto la cadena se corta con
algo que el cliente ya sabe encontrar solo — DNS, una IP virtual, un túnel.

---

## 4 · Flujo del deploy blue-green — con abort y rollback

![diagram](./diagramas-4.png)

**Dos verificaciones, no una.** El health check dice "estoy vivo", no "soy la versión que
pediste": un ship a medias deja un contenedor perfectamente sano corriendo la versión
anterior. Por eso el segundo verify compara la versión que responde `Identidad` contra la
declarada en el código.

**La vieja no se baja al conmutar.** Volver atrás tiene que ser un comando, no un deploy en
reversa.

---

## 5 · Secuencia — reparto, muerte de una réplica y ejección

![diagram](./diagramas-5.png)

**Por qué 3 fallos y no 1:** con umbral 1, un pico de latencia o un GC bastan para expulsar
una réplica sana. Nos pasó: bajo carga los chequeos se pasaban de deadline y el pool quedaba
vacío **con las dos réplicas perfectamente vivas**. Expulsar rápido es caro porque achica el
pool; reincorporar rápido es barato, así que alcanza **un** acierto para volver.

---

## 6 · Secuencia — dos réplicas dando de alta el mismo legajo

![diagram](./diagramas-6.png)

**Sin atomicidad, las dos ganan.** Entre comprobar que el legajo no está y escribirlo, la
otra réplica se cuela; y entre pedir el `id` y usarlo, la otra pide el mismo. Redis corre el
script entero sin intercalar comandos de otros clientes, así que las cinco operaciones valen
por una — y eso nos ahorra coordinar las casas entre sí.

**Medido:** 25 altas del mismo legajo lanzadas a la vez desde una barrera común →
**1 `OK` y 24 `ALREADY_EXISTS`**.

---

## 7 · El hallazgo: L4 reparte por conexión, no por RPC

![diagram](./diagramas-7.png)

Un balanceador **L4 decide por conexión**. Una conexión gRPC es persistente y multiplexada:
el cliente abre un canal y manda todos sus RPC por ahí, así que queda pegado a una réplica.
El contrato lo avisa en §7.3 — acá está medido: **100 % a una sola réplica** con canal
compartido, **50/50 exacto** con canal por request.

Repartir **por RPC** exige subir a L7 y entender HTTP/2. Es la decisión grande que le queda
al equipo Plataforma.

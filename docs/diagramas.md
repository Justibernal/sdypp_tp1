# Diagramas — App Java · Tarea Clase 2

Cinco diagramas: la arquitectura de cada etapa, el flujo del deploy y las dos secuencias
que explican por qué el sistema aguanta. Lo que está implementado y medido va en línea
llena; lo propuesto, punteado.

---

## 1 · Arquitectura — Etapa 1: un conmutador, dos colores

Termina la pelea por el puerto: una sola URL pública, y detrás dos versiones conviviendo.

```mermaid
flowchart LR
    CLI["Cliente gRPC<br/>(verificador)"]

    CONM["CONMUTADOR<br/>:8080 plano de datos (TCP)<br/>:9090 plano de control (HTTP)"]

    subgraph AZUL ["AZUL — versión que sirve"]
        B1["réplica blue-1<br/>:8111 · v5"]
        B2["réplica blue-2<br/>:8112 · v5"]
    end

    subgraph VERDE ["VERDE — versión nueva, al lado"]
        G1["réplica green-1<br/>:8121 · v6"]
        G2["réplica green-2<br/>:8122 · v6"]
    end

    REDIS[("Redis<br/>estado compartido")]
    DEP["deploy.sh"]

    CLI -->|"una única URL"| CONM
    CONM ==>|"apunta acá"| B1
    CONM ==> B2
    CONM -.->|"tras conmutar"| G1
    CONM -.-> G2

    B1 --> REDIS
    B2 --> REDIS
    G1 --> REDIS
    G2 --> REDIS

    DEP -->|"POST /backends<br/>sin reiniciar"| CONM

    classDef activo fill:#1f6feb,stroke:#0b3d91,color:#fff
    classDef espera fill:#2ea043,stroke:#12511f,color:#fff
    classDef infra fill:#6e7681,stroke:#30363d,color:#fff
    class B1,B2 activo
    class G1,G2 espera
    class CONM,DEP,REDIS infra
```

**Lo que importa:** el conmutador tiene **dos planos separados**. El de datos reenvía bytes;
el de control escucha aparte, en `127.0.0.1`. Esa separación es lo que permite cambiar de
destino **sin reiniciar** — el deploy le habla al control mientras el de datos sigue
atendiendo.

---

## 2 · Arquitectura — Etapa 2: pool mixto y health checks

El conmutador ya no apunta a un backend: conoce una lista, rota entre ella, y saca de
rotación a la que deja de responder.

```mermaid
flowchart TB
    CLI["Clientes gRPC"] --> BAL

    BAL["BALANCEADOR<br/>round-robin sobre los SANOS<br/>AtomicInteger = sección crítica<br/><br/>health cada 3 s · grpc.health.v1.Health<br/>3 fallos seguidos = fuera de rotación"]

    subgraph CJ ["casa-justino"]
        J1["Java v7<br/>:8121"]
        J2["Java v7<br/>:8122"]
        LOGJ["bitácora local<br/>una por réplica"]
    end

    subgraph CT ["casa-tomas"]
        P1["Python<br/>:8080"]
    end

    subgraph CS ["casa-salvador"]
        P2["Python<br/>:8080"]
    end

    BAL --> J1
    BAL --> J2
    BAL -.->|"pendiente: Tailscale"| P1
    BAL -.->|"pendiente: Tailscale"| P2

    REDIS[("Redis · casa-nomico<br/>personas:seq · persona:id<br/>personas:index · legajo:legajo")]

    J1 --> REDIS
    J2 --> REDIS
    P1 -.-> REDIS
    P2 -.-> REDIS

    LOGB["bitácora del<br/>balanceador<br/>a quién derivó"]
    J1 --> LOGJ
    J2 --> LOGJ
    BAL --> LOGB
    LOGB -.->|"se cruzan para auditar<br/>una operación puntual"| LOGJ

    classDef java fill:#1f6feb,stroke:#0b3d91,color:#fff
    classDef py fill:#8957e5,stroke:#4c2889,color:#fff
    classDef infra fill:#6e7681,stroke:#30363d,color:#fff
    classDef log fill:#9e6a03,stroke:#5c3d00,color:#fff
    class J1,J2 java
    class P1,P2 py
    class BAL,REDIS infra
    class LOGJ,LOGB log
```

**El estado no vive en las réplicas.** Por eso una puede atender el alta y otra la lectura
siguiente, y por eso la que se muere no se lleva nada consigo. El SPOF se mudó: ahora está
en Redis.

---

## 3 · Arquitectura — Etapa 3: replicar al balanceador (propuesta)

```mermaid
flowchart TB
    CLI["Cliente"] -->|"¿a cuál le habla?<br/>← el problema nuevo"| VIP

    VIP{{"punto de entrada único<br/>DNS / IP virtual / túnel"}}

    VIP --> BAL1["balanceador A<br/>LÍDER"]
    VIP -.-> BAL2["balanceador B<br/>en espera"]

    BAL1 <-.->|"latido<br/>¿sigue vivo?"| BAL2

    BAL1 --> POOL["pool de réplicas"]
    BAL2 -.-> POOL

    SPLIT["si se corta el enlace<br/>los dos se creen líderes<br/><b>split-brain</b>"]
    BAL1 -.- SPLIT
    BAL2 -.- SPLIT

    classDef prop fill:#30363d,stroke:#8b949e,color:#fff,stroke-dasharray: 5 5
    classDef riesgo fill:#da3633,stroke:#8b0000,color:#fff
    class VIP,BAL1,BAL2,POOL prop
    class SPLIT riesgo
```

Replicar el balanceador no cierra el problema, lo **corre de lugar**: aparece el split-brain
y la pregunta de quién balancea a los balanceadores. En algún punto la cadena se corta con
algo que el cliente ya sabe encontrar solo — DNS, una IP virtual, un túnel.

---

## 4 · Flujo del deploy blue-green — con abort y rollback

```mermaid
flowchart TD
    START(["cambio en el código<br/>Config.VERSION sube"]) --> BUILD

    BUILD["BUILD<br/>docker build multi-stage<br/>tag = vN-commit(-sucio)"] --> ARRIBA

    ARRIBA["ARRIBA<br/>las N réplicas del color LIBRE<br/>todas a la vez, en otro puerto"] --> VSANA

    VSANA{"VERIFY 1<br/>¿todas healthy?"}
    VSANA -->|"no"| ABORT
    VSANA -->|"sí"| VVER

    VVER{"VERIFY 2<br/>¿todas sirven la<br/>versión declarada?"}
    VVER -->|"no — ship a medias"| ABORT
    VVER -->|"sí"| CONM

    ABORT["ABORTA<br/>baja TODAS las nuevas<br/>NO conmuta · exit 1<br/>las viejas nunca dejaron de servir"]

    CONM["CONMUTAR<br/>POST /backends al conmutador<br/>sin reiniciarlo"] --> OK

    OK(["sirviendo la versión nueva<br/>la vieja QUEDA VIVA al lado"])
    OK -.->|"si algo sale mal después"| RB["ROLLBACK<br/>volver a apuntar a la vieja<br/>un solo comando"]

    style VSANA fill:#d29922,stroke:#7d4e00,color:#111
    style VVER fill:#d29922,stroke:#7d4e00,color:#111
    style ABORT fill:#da3633,stroke:#8b0000,color:#fff
    style RB fill:#da3633,stroke:#8b0000,color:#fff
    style OK fill:#2ea043,stroke:#12511f,color:#fff
```

**Dos verificaciones, no una.** El health check dice "estoy vivo", no "soy la versión que
pediste": un ship a medias deja un contenedor perfectamente sano corriendo la versión
anterior. Por eso el segundo verify compara la versión que responde `Identidad` contra la
declarada en el código.

**La vieja no se baja al conmutar.** Volver atrás tiene que ser un comando, no un deploy en
reversa.

---

## 5 · Secuencia — reparto, muerte de una réplica y ejección

```mermaid
sequenceDiagram
    autonumber
    participant C as Cliente
    participant B as Balanceador
    participant R1 as réplica 1
    participant R2 as réplica 2
    participant DB as Redis

    Note over B,R2: cada 3 s, en paralelo al tráfico
    B->>R1: grpc.health.v1.Health
    R1-->>B: SERVING
    B->>R2: grpc.health.v1.Health
    R2-->>B: SERVING

    C->>B: CrearPersona
    B->>R1: reenvía (round-robin)
    R1->>DB: script Lua atómico
    DB-->>R1: id = 7
    R1-->>C: OK · id 7 · servidoPor java

    C->>B: ListarPersonas
    B->>R2: reenvía (le toca a la otra)
    R2->>DB: ZRANGE + HGETALL
    DB-->>R2: incluye el id 7
    R2-->>C: la persona está

    Note over R1: la réplica 1 muere

    B->>R1: health check
    R1--xB: sin respuesta (fallo 1)
    B->>R1: health check
    R1--xB: sin respuesta (fallo 2)
    B->>R1: health check
    R1--xB: sin respuesta (fallo 3)
    Note over B: 3 seguidos → fuera de rotación

    C->>B: ListarPersonas
    B->>R2: todo a la que queda
    R2-->>C: OK · los datos siguen estando
```

**Por qué 3 fallos y no 1:** con umbral 1, un pico de latencia o un GC bastan para expulsar
una réplica sana. Nos pasó: bajo carga los chequeos se pasaban de deadline y el pool quedaba
vacío **con las dos réplicas perfectamente vivas**. Expulsar rápido es caro porque achica el
pool; reincorporar rápido es barato, así que alcanza **un** acierto para volver.

---

## 6 · Secuencia — dos réplicas dando de alta el mismo legajo

```mermaid
sequenceDiagram
    autonumber
    participant A as réplica Java
    participant P as réplica Python
    participant R as Redis (mono-hilo)

    par al mismo tiempo
        A->>R: EVAL alta_persona · legajo 100200
    and
        P->>R: EVAL alta_persona · legajo 100200
    end

    Note over R: Redis ejecuta un script<br/>entero sin intercalar otros

    R->>R: EXISTS legajo:100200 → no
    R->>R: INCR personas:seq → 7
    R->>R: HSET persona:7 · ZADD index · SET legajo:100200
    R-->>A: 7

    R->>R: EXISTS legajo:100200 → SÍ
    R-->>P: -1

    A-->>A: OK · id 7
    P-->>P: ALREADY_EXISTS
```

**Sin atomicidad, las dos ganan.** Entre comprobar que el legajo no está y escribirlo, la
otra réplica se cuela; y entre pedir el `id` y usarlo, la otra pide el mismo. Redis corre el
script entero sin intercalar comandos de otros clientes, así que las cinco operaciones valen
por una — y eso nos ahorra coordinar las casas entre sí.

**Medido:** 25 altas del mismo legajo lanzadas a la vez desde una barrera común →
**1 `OK` y 24 `ALREADY_EXISTS`**.

---

## 7 · El hallazgo: L4 reparte por conexión, no por RPC

```mermaid
flowchart TB
    subgraph A ["❌ cliente gRPC real — un canal compartido"]
        CA["cliente"] -->|"una conexión TCP<br/>N RPC multiplexados"| BA["balanceador L4"]
        BA -->|"elige UNA VEZ"| RA["réplica 1<br/>100 %"]
        BA -.->|"nunca"| RB2["réplica 2<br/>0 %"]
    end

    subgraph B ["✅ N clientes distintos — un canal por request"]
        CB["N clientes"] -->|"N conexiones TCP"| BB["balanceador L4"]
        BB -->|"elige por conexión"| RC["réplica 1<br/>50 %"]
        BB --> RD["réplica 2<br/>50 %"]
    end

    classDef mal fill:#da3633,stroke:#8b0000,color:#fff
    classDef bien fill:#2ea043,stroke:#12511f,color:#fff
    class RA,RB2 mal
    class RC,RD bien
```

Un balanceador **L4 decide por conexión**. Una conexión gRPC es persistente y multiplexada:
el cliente abre un canal y manda todos sus RPC por ahí, así que queda pegado a una réplica.
El contrato lo avisa en §7.3 — acá está medido: **100 % a una sola réplica** con canal
compartido, **50/50 exacto** con canal por request.

Repartir **por RPC** exige subir a L7 y entender HTTP/2. Es la decisión grande que le queda
al equipo Plataforma.

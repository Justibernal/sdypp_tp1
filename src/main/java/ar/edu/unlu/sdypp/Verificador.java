package ar.edu.unlu.sdypp;

import ar.edu.unlu.sdypp.contrato.*;
import io.grpc.*;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verificador de la demo. El enunciado pide que la demo se mida y no se mire, y que la
 * corra otro equipo: dispara N requests contra la URL pública, cuenta códigos y muestra
 * cómo se repartieron entre las instancias.
 *
 * <pre>
 *   carga        &lt;destino&gt; &lt;N&gt; [hilos]     N Identidad; codigos + reparto por instancia
 *   concurrencia &lt;destino&gt; &lt;legajo&gt; &lt;N&gt;   N altas del MISMO legajo a la vez
 *   secuencia    &lt;destino&gt; &lt;legajo&gt;       un alta y la lectura siguiente
 * </pre>
 */
public final class Verificador {

    private Verificador() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("uso: Verificador <carga|concurrencia|secuencia> <host:puerto> [...]");
            System.exit(2);
        }
        String modo = args[0];
        String destino = args[1];

        // Un solo canal compartido por todos los hilos: es como habla un cliente real de
        // gRPC. Ojo en la demo — si el balanceador reparte por CONEXIÓN y no por RPC,
        // con un canal único todas las requests caen en la misma réplica y el reparto no
        // se ve. Ese es el hallazgo, no un error del verificador.
        ManagedChannel canal = Grpc.newChannelBuilder(destino, InsecureChannelCredentials.create()).build();
        try {
            switch (modo) {
                case "carga" -> carga(canal, destino,
                        Integer.parseInt(args[2]),
                        args.length > 3 ? Integer.parseInt(args[3]) : 8);
                case "concurrencia" -> concurrencia(canal, destino,
                        Integer.parseInt(args[2]), Integer.parseInt(args[3]));
                case "secuencia" -> secuencia(canal, destino, Integer.parseInt(args[2]));
                default -> {
                    System.err.println("modo desconocido: " + modo);
                    System.exit(2);
                }
            }
        } finally {
            canal.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    /** N Identidad: cuenta códigos y a qué instancia fue cada una. */
    private static void carga(ManagedChannel canal, String destino, int n, int hilos) throws Exception {
        // ConcurrentHashMap y no HashMap: varios hilos cuentan sobre el mismo mapa y un
        // HashMap se corrompe con escrituras concurrentes. Es la sección crítica del
        // enunciado — la misma que tiene el contador round-robin del balanceador.
        Map<String, AtomicInteger> porInstancia = new ConcurrentHashMap<>();
        Map<String, AtomicInteger> porCodigo = new ConcurrentHashMap<>();

        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        long inicio = System.nanoTime();
        CountDownLatch listos = new CountDownLatch(n);

        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                try {
                    Instancia r = ServicioGrpc.newBlockingStub(canal)
                            .withDeadlineAfter(5, TimeUnit.SECONDS)
                            .identidad(IdentidadPedido.getDefaultInstance());
                    contar(porCodigo, "OK");
                    contar(porInstancia, r.getApp() + "@" + r.getHost() + " v" + r.getVersion());
                } catch (StatusRuntimeException e) {
                    contar(porCodigo, e.getStatus().getCode().name());
                    contar(porInstancia, "(sin respuesta)");
                } finally {
                    listos.countDown();
                }
            });
        }
        listos.await();
        pool.shutdown();
        long ms = (System.nanoTime() - inicio) / 1_000_000;

        int ok = porCodigo.getOrDefault("OK", new AtomicInteger()).get();
        System.out.println("=== Verificador · carga · " + destino + " ===");
        System.out.printf("requests=%d  hilos=%d  tiempo=%dms  ok=%d  fallidas=%d%n",
                n, hilos, ms, ok, n - ok);
        System.out.println("\n-- códigos --");
        porCodigo.forEach((k, v) -> System.out.printf("  %-20s %5d  (%.1f%%)%n", k, v.get(), 100.0 * v.get() / n));
        System.out.println("\n-- reparto por instancia --");
        porInstancia.forEach((k, v) -> System.out.printf("  %-38s %5d  (%.1f%%)%n", k, v.get(), 100.0 * v.get() / n));
    }

    /**
     * N altas del MISMO legajo, todas a la vez. Si el alta es atómica tiene que salir
     * exactamente 1 OK y N-1 ALREADY_EXISTS. Si saliera más de un OK, entre el "existe?"
     * y el "escribo" se coló otra réplica: es la condición de carrera que el script Lua
     * de Redis evita.
     */
    private static void concurrencia(ManagedChannel canal, String destino, int legajo, int n) throws Exception {
        Map<String, AtomicInteger> codigos = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(n);
        // Todos los hilos esperan en la misma barrera y salen juntos: si se lanzaran de a
        // uno, el primero terminaría antes de que arranque el segundo y no habría carrera.
        CyclicBarrier largada = new CyclicBarrier(n);
        CountDownLatch listos = new CountDownLatch(n);

        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                try {
                    largada.await();
                    ServicioGrpc.newBlockingStub(canal)
                            .withDeadlineAfter(5, TimeUnit.SECONDS)
                            .crearPersona(NuevaPersona.newBuilder()
                                    .setNombre("Carrera " + legajo).setLegajo(legajo).build());
                    contar(codigos, "OK");
                } catch (StatusRuntimeException e) {
                    contar(codigos, e.getStatus().getCode().name());
                } catch (Exception e) {
                    contar(codigos, "ERROR_CLIENTE");
                } finally {
                    listos.countDown();
                }
            });
        }
        listos.await();
        pool.shutdown();

        System.out.println("=== Verificador · concurrencia · legajo=" + legajo + " · " + n + " altas simultáneas ===");
        codigos.forEach((k, v) -> System.out.printf("  %-20s %5d%n", k, v.get()));
        int ok = codigos.getOrDefault("OK", new AtomicInteger()).get();
        System.out.println(ok == 1
                ? "  ✅ exactamente 1 alta ganó: el alta es atómica"
                : "  ❌ " + ok + " altas ganaron: hay condición de carrera");
    }

    /** Un alta y la lectura siguiente: prueba que el estado sobrevive al reparto. */
    private static void secuencia(ManagedChannel canal, String destino, int legajo) {
        ServicioGrpc.ServicioBlockingStub stub = ServicioGrpc.newBlockingStub(canal);
        RespuestaPersona alta = stub.crearPersona(NuevaPersona.newBuilder()
                .setNombre("Verificacion " + legajo).setLegajo(legajo).build());
        ListaPersonas lista = stub.listarPersonas(ListarPersonasPedido.getDefaultInstance());

        boolean encontrada = lista.getPersonasList().stream()
                .anyMatch(p -> p.getId() == alta.getPersona().getId());

        System.out.println("=== Verificador · secuencia ===");
        System.out.printf("  alta   atendida por: %s  -> id=%d%n", alta.getServidoPor(), alta.getPersona().getId());
        System.out.printf("  lectura atendida por: %s  -> %d personas%n", lista.getServidoPor(), lista.getPersonasCount());
        System.out.println(encontrada
                ? "  ✅ el dato está: el estado vive en la base, no en la instancia"
                : "  ❌ el dato no aparece en la lectura");
    }

    private static void contar(Map<String, AtomicInteger> mapa, String clave) {
        mapa.computeIfAbsent(clave, k -> new AtomicInteger()).incrementAndGet();
    }
}

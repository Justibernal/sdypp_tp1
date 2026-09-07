package ar.edu.unlu.sdypp;

import ar.edu.unlu.sdypp.contrato.ServicioGrpc;
import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * App Java — servidor gRPC del contrato v2.2.
 *
 * El esquema formal está en src/main/proto/contrato.proto; las reglas que el .proto no
 * puede expresar (validación, orden de los chequeos, semántica de los errores) están en
 * CONTRATO.md. Ante una diferencia, manda el contrato.
 *
 * <pre>
 *   ./mvnw -q package
 *   java -jar target/app-java.jar 8080
 * </pre>
 */
public final class AppJava {

    /** Segundos que se le dan a los RPC en curso antes de cortar. */
    private static final int GRACIA_SEGUNDOS = 10;

    private AppJava() {
    }

    public static void main(String[] args) throws Exception {
        int puerto = Config.puerto(args);
        RepositorioPersonas repositorio = RepositorioPersonas.crear();

        // Health checking estándar de gRPC, además del RPC Salud del contrato: es lo que
        // entienden el HEALTHCHECK del contenedor y las herramientas del balanceador.
        HealthStatusManager salud = new HealthStatusManager();

        // Pool acotado y no cachedThreadPool: con hilos ilimitados una ráfaga del
        // verificador abriría cientos de hilos y la réplica se cae por falta de memoria
        // en vez de encolar. TP_WORKERS lo hace ajustable sin recompilar.
        ExecutorService hilos = Executors.newFixedThreadPool(Config.WORKERS);

        Server servidor = Grpc.newServerBuilderForPort(puerto, InsecureServerCredentials.create())
                // Canal inseguro a propósito: el cifrado entre casas lo pone Tailscale
                // por debajo (CONTRATO.md §1). Nunca se expone al mundo sin el túnel.
                .executor(hilos)
                .addService(new ServicioImpl(repositorio))
                .addService(salud.getHealthService())
                // Reflection: permite que otro equipo nos pruebe con grpcurl sin tener el
                // .proto. Es lo que hace posible el verificador cruzado.
                .addService(ProtoReflectionServiceV1.newInstance())
                .build()
                .start();

        salud.setStatus("", ServingStatus.SERVING);
        salud.setStatus("sdypp.Servicio", ServingStatus.SERVING);

        System.out.printf("Servidor gRPC en 0.0.0.0:%d (PID: %d) (Arrancado: %s)%n",
                puerto, ProcessHandle.current().pid(), Config.ARRANCADO);
        System.out.printf("[instancia] %s@%s host=%s version=%d workers=%d%n",
                Config.APP, Config.CASA, Config.HOST, Config.VERSION, Config.WORKERS);
        if (repositorio == null) {
            System.out.println("[personas] sin TP_REDIS_URL: los RPC de personas responden UNAVAILABLE");
        } else {
            System.out.println("[personas] base compartida en " + Config.urlSinCredenciales(Config.REDIS_URL));
        }
        System.out.println("[bitacora] " + Bitacora.archivo().toAbsolutePath());

        // El hook corre con SIGTERM (docker stop) y con SIGINT (Ctrl+C).
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[ Graceful Shutdown ] Señal recibida. Marcando NOT_SERVING y drenando...");
            // Primero no-sana: el balanceador la saca de rotación y deja de mandarle RPCs
            // nuevos mientras todavía está atendiendo los que tiene.
            salud.enterTerminalState();
            servidor.shutdown();
            try {
                // shutdown() deja de aceptar RPCs nuevos y espera a los en curso: sin esto
                // las peticiones que estaban a mitad de camino se cortarían justo durante
                // el deploy, que es cuando el servicio tiene que seguir respondiendo.
                if (!servidor.awaitTermination(GRACIA_SEGUNDOS, TimeUnit.SECONDS)) {
                    servidor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                servidor.shutdownNow();
            }
            hilos.shutdown();
            System.out.println("[ Graceful Shutdown ] Puerto liberado y servidor detenido exitosamente.");
        }));

        servidor.awaitTermination();
    }
}

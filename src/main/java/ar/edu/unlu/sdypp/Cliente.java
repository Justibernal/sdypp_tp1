package ar.edu.unlu.sdypp;

import ar.edu.unlu.sdypp.contrato.*;
import io.grpc.*;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthGrpc;

import java.util.concurrent.TimeUnit;

/**
 * Cliente de línea de comandos. Con gRPC no alcanza un curl: el mensaje va en binario
 * sobre HTTP/2 y hace falta un stub. Sirve para probar a mano y como base del
 * verificador cruzado.
 *
 * <pre>
 *   java -cp target/app-java.jar ar.edu.unlu.sdypp.Cliente localhost:8080 identidad
 *   java -cp target/app-java.jar ar.edu.unlu.sdypp.Cliente localhost:8080 salud
 *   java -cp target/app-java.jar ar.edu.unlu.sdypp.Cliente localhost:8080 echo hola
 *   java -cp target/app-java.jar ar.edu.unlu.sdypp.Cliente localhost:8080 personas
 *   java -cp target/app-java.jar ar.edu.unlu.sdypp.Cliente localhost:8080 alta "Ada Lovelace" 100200
 * </pre>
 */
public final class Cliente {

    private Cliente() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("uso: Cliente <host:puerto> <identidad|salud|health|echo|personas|alta> [...]");
            System.exit(2);
        }
        String destino = args[0];
        String comando = args[1];

        // Un canal, no una conexión por request: gRPC multiplexa varios RPC sobre la
        // misma conexión HTTP/2. Es justo lo que complica el balanceo por conexión.
        ManagedChannel canal = Grpc.newChannelBuilder(destino, InsecureChannelCredentials.create()).build();
        try {
            ServicioGrpc.ServicioBlockingStub stub = ServicioGrpc.newBlockingStub(canal)
                    .withDeadlineAfter(5, TimeUnit.SECONDS);

            switch (comando) {
                case "identidad" -> imprimir(stub.identidad(IdentidadPedido.getDefaultInstance()));
                case "salud" -> imprimir(stub.salud(SaludPedido.getDefaultInstance()));
                case "echo" -> imprimir(stub.echo(PingPedido.newBuilder()
                        .setPing(args.length > 2 ? args[2] : "").build()));
                case "personas" -> imprimir(stub.listarPersonas(ListarPersonasPedido.getDefaultInstance()));
                case "alta" -> {
                    if (args.length < 4) {
                        System.err.println("uso: ... alta <nombre> <legajo>");
                        System.exit(2);
                    }
                    imprimir(stub.crearPersona(NuevaPersona.newBuilder()
                            .setNombre(args[2])
                            .setLegajo(Integer.parseInt(args[3]))
                            .build()));
                }
                // El health estándar, el mismo que consulta el balanceador.
                case "health" -> imprimir(HealthGrpc.newBlockingStub(canal)
                        .withDeadlineAfter(5, TimeUnit.SECONDS)
                        .check(HealthCheckRequest.newBuilder().setService("").build()));
                default -> {
                    System.err.println("comando desconocido: " + comando);
                    System.exit(2);
                }
            }
        } catch (StatusRuntimeException e) {
            // El error de gRPC llega tipado: código + descripción, los del contrato.
            System.out.println("ERROR " + e.getStatus().getCode() + ": " + e.getStatus().getDescription());
            System.exit(1);
        } finally {
            canal.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    private static void imprimir(Object mensaje) {
        System.out.println(mensaje.toString().strip());
    }
}

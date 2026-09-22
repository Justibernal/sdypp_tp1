package ar.edu.unlu.sdypp;

import ar.edu.unlu.sdypp.contrato.*;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

/**
 * Los cinco RPC del contrato (CONTRATO.md §2). Mismas firmas que la App Python: por eso
 * las dos son intercambiables detrás del balanceador.
 *
 * <p>Esta clase es sólo el transporte gRPC: valida nada por su cuenta y resuelve nada por
 * su cuenta. La lógica está en {@link Operaciones}, compartida con el worker de la cola,
 * porque la misma operación tiene que dar el mismo código por los dos caminos.
 */
public final class ServicioImpl extends ServicioGrpc.ServicioImplBase {

    private final Operaciones operaciones;

    public ServicioImpl(RepositorioPersonas repositorio) {
        this.operaciones = new Operaciones(repositorio);
    }

    @Override
    public void identidad(IdentidadPedido pedido, StreamObserver<Instancia> respuesta) {
        despachar("Identidad", operaciones.identidad(), respuesta);
    }

    @Override
    public void salud(SaludPedido pedido, StreamObserver<EstadoSalud> respuesta) {
        despachar("Salud", operaciones.salud(), respuesta);
    }

    @Override
    public void echo(PingPedido pedido, StreamObserver<PongRespuesta> respuesta) {
        despachar("Echo", operaciones.echo(pedido.getPing()), respuesta);
    }

    @Override
    public void listarPersonas(ListarPersonasPedido pedido, StreamObserver<ListaPersonas> respuesta) {
        despachar("ListarPersonas", operaciones.listarPersonas(), respuesta);
    }

    @Override
    public void crearPersona(NuevaPersona pedido, StreamObserver<RespuestaPersona> respuesta) {
        despachar("CrearPersona",
                operaciones.crearPersona(pedido.getNombre(), pedido.getLegajo()), respuesta);
    }

    /**
     * Traduce el resultado de la operación a gRPC: una línea de bitácora y, o el mensaje,
     * o el código de error con su descripción.
     */
    @SuppressWarnings("unchecked")
    private static <T> void despachar(String rpc, Operaciones.Resultado resultado,
                                      StreamObserver<T> respuesta) {
        if (resultado.fallo()) {
            if (resultado.detalle != null) {
                System.out.println("[personas] la base no respondió: " + resultado.detalle);
            }
            Bitacora.registrar(rpc, resultado.codigo.name());
            StatusRuntimeException error = Status.fromCode(resultado.codigo)
                    .withDescription(resultado.mensaje)
                    .asRuntimeException();
            respuesta.onError(error);
            return;
        }
        Bitacora.registrar(rpc, "OK", resultado.id);
        // onNext + onCompleted: en un RPC unario la respuesta es un único mensaje.
        respuesta.onNext((T) resultado.valor);
        respuesta.onCompleted();
    }
}

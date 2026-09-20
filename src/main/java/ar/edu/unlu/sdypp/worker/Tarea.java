package ar.edu.unlu.sdypp.worker;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Un pedido tomado de la cola, tal como lo entrega el servicio del equipo de la cola.
 *
 * <pre>
 *   {"id": "a3f9", "operacion": "POST /personas",
 *    "parametros": {"nombre": "Ada Lovelace", "legajo": 100200},
 *    "idempotente": false, "cliente": "10.0.0.7", "quedaMs": 4200, "intento": 0}
 * </pre>
 *
 * <p><b>Toda la tolerancia al formato vive acá.</b> El servicio de cola lo escribe otro
 * equipo y su capa HTTP todavía no está publicada: si mañana un campo cambia de nombre o
 * llega como número en vez de string, se toca esta clase y nada más. El resto del worker
 * trabaja contra estos siete campos.
 *
 * <p>Un campo que falta no es una excepción: es un valor por defecto. Una tarea que llega
 * media rota se responde igual con un código del contrato —que es información para el otro
 * equipo— en vez de tumbar el hilo del worker y dejar al pedido esperando a que le venza la
 * reserva.
 */
public final class Tarea {

    /** Sin presupuesto declarado. No es 0: para la cola, 0 significa "ya venció". */
    public static final long SIN_PRESUPUESTO = -1;

    public final String id;
    public final String operacion;
    public final JsonObject parametros;
    public final boolean idempotente;
    public final String cliente;
    public final long quedaMs;
    public final int intento;

    private Tarea(String id, String operacion, JsonObject parametros, boolean idempotente,
                  String cliente, long quedaMs, int intento) {
        this.id = id;
        this.operacion = operacion;
        this.parametros = parametros;
        this.idempotente = idempotente;
        this.cliente = cliente;
        this.quedaMs = quedaMs;
        this.intento = intento;
    }

    public static Tarea desde(JsonObject sobre) {
        return new Tarea(
                // El id puede venir como string o como número: la cola lo genera y todavía
                // no dijo cuál de los dos. Se guarda siempre como texto, que sirve para los
                // dos casos, y se devuelve tal cual vino.
                texto(sobre, "id"),
                texto(sobre, "operacion"),
                objeto(sobre, "parametros"),
                booleano(sobre, "idempotente"),
                texto(sobre, "cliente"),
                entero(sobre, "quedaMs", SIN_PRESUPUESTO),
                (int) entero(sobre, "intento", 0));
    }

    /** Si ya se le acabó el presupuesto no hay que ejecutarla: nadie va a leer la respuesta. */
    public boolean vencida() {
        return quedaMs != SIN_PRESUPUESTO && quedaMs <= 0;
    }

    public boolean valida() {
        return id != null && !id.isBlank() && operacion != null && !operacion.isBlank();
    }

    @Override
    public String toString() {
        return "tarea " + id + " · " + operacion + (intento > 0 ? " (intento " + intento + ")" : "");
    }

    private static String texto(JsonObject o, String clave) {
        JsonElement e = o.get(clave);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
            return null;
        }
        return e.getAsString();
    }

    private static JsonObject objeto(JsonObject o, String clave) {
        JsonElement e = o.get(clave);
        return (e != null && e.isJsonObject()) ? e.getAsJsonObject() : new JsonObject();
    }

    private static boolean booleano(JsonObject o, String clave) {
        JsonElement e = o.get(clave);
        if (e == null || !e.isJsonPrimitive()) {
            // Sin el campo se asume NO idempotente, que es el lado seguro: la cola sólo
            // reencola las idempotentes, y darle por defecto el permiso de repetir un alta
            // sería crear personas duplicadas por un campo que faltaba.
            return false;
        }
        try {
            return e.getAsBoolean();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static long entero(JsonObject o, String clave, long porDefecto) {
        JsonElement e = o.get(clave);
        if (e == null || !e.isJsonPrimitive()) {
            return porDefecto;
        }
        try {
            return e.getAsLong();
        } catch (RuntimeException ex) {
            return porDefecto;
        }
    }
}

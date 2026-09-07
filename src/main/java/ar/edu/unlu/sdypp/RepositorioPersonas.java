package ar.edu.unlu.sdypp;

import ar.edu.unlu.sdypp.contrato.Persona;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Acceso a la base compartida con el esquema de claves de CONTRATO.md §4.
 *
 * El esquema es contrato tanto como el .proto: si esta app guardara la misma persona
 * bajo otra clave o con otra estructura, las dos implementaciones escribirían en la
 * misma base sin encontrar lo del otro.
 *
 * <pre>
 *   personas:seq        string  contador; INCR devuelve el id de la próxima persona
 *   persona:&lt;id&gt;        hash    nombre, legajo
 *   personas:index      zset    miembro y score &lt;id&gt;; mantiene el orden de listado
 *   legajo:&lt;legajo&gt;     string  &lt;id&gt;; se crea atómicamente para detectar duplicados
 * </pre>
 */
public final class RepositorioPersonas {

    /** La base no respondió. Se traduce en UNAVAILABLE. */
    public static class BaseNoDisponible extends RuntimeException {
        public BaseNoDisponible(Throwable causa) {
            super(causa);
        }
    }

    /**
     * El alta tiene que ser atómica de punta a punta. Entre comprobar que el legajo no
     * está registrado y escribirlo, otra réplica puede colarse con el mismo; y entre
     * pedir el id y usarlo, otra puede pedir el mismo. Redis corre el script entero sin
     * intercalar comandos de otros clientes, así que las cinco operaciones valen por
     * una. Es lo que nos ahorra coordinar las casas entre sí para dar de alta a alguien.
     *
     * Byte por byte el mismo script que usa la App Python: si difirieran, las dos apps
     * dejarían la base en estructuras distintas.
     */
    private static final String LUA_ALTA_PERSONA = """
            local clave_legajo = KEYS[1]
            local nombre = ARGV[1]
            local legajo = ARGV[2]

            if redis.call('EXISTS', clave_legajo) == 1 then
              return -1
            end

            local id = redis.call('INCR', 'personas:seq')
            redis.call('HSET', 'persona:' .. id, 'nombre', nombre, 'legajo', legajo)
            redis.call('ZADD', 'personas:index', id, id)
            redis.call('SET', clave_legajo, id)
            return id
            """;

    private final JedisPool pool;

    private RepositorioPersonas(JedisPool pool) {
        this.pool = pool;
    }

    /**
     * Prepara el acceso a la base. Sin TP_REDIS_URL devuelve null y los RPC de personas
     * responden UNAVAILABLE.
     *
     * Un fallo acá (URL mal escrita, base caída) no tiene que impedir que la réplica
     * arranque: los demás RPC no dependen de la base, y el pool se conecta recién al
     * primer uso, así que la réplica se recupera sola cuando la base vuelve.
     */
    public static RepositorioPersonas crear() {
        if (Config.REDIS_URL.isBlank()) {
            return null;
        }
        try {
            GenericObjectPoolConfig<Jedis> config = new GenericObjectPoolConfig<>();
            // testOnBorrow: el pool hace PING antes de entregar la conexión y descarta
            // las muertas. Sin esto, si la base se cae y vuelve, el pool sigue repartiendo
            // las conexiones rotas de antes y la réplica queda dando UNAVAILABLE para
            // siempre — hay que reiniciarla a mano, que es justo lo que no queremos en un
            // sistema que tiene que aguantar que una pieza se caiga y vuelva.
            config.setTestOnBorrow(true);
            config.setMaxTotal(Config.WORKERS * 2);

            // Timeouts cortos: sin ellos, con la base caída el RPC queda colgado y el
            // hilo del pool no vuelve. Mejor fallar rápido con UNAVAILABLE.
            JedisPool pool = new JedisPool(config, URI.create(Config.REDIS_URL), 1000);
            return new RepositorioPersonas(pool);
        } catch (RuntimeException e) {
            System.out.println("[personas] no se pudo preparar el acceso a la base (" + e + ")");
            return null;
        }
    }

    /**
     * Personas ordenadas por id ascendente. El orden sale del sorted set: sin un orden
     * fijo, dos réplicas devuelven el mismo conjunto en distinta secuencia y el servicio
     * parece errático.
     */
    public List<Persona> listar() {
        try (Jedis jedis = pool.getResource()) {
            List<String> ids = jedis.zrange("personas:index", 0, -1);

            // Un round-trip por persona multiplicaría la latencia por la cantidad de
            // filas; el pipeline manda todos los HGETALL juntos y lee las respuestas
            // en orden.
            Pipeline tuberia = jedis.pipelined();
            List<Response<Map<String, String>>> respuestas = new ArrayList<>(ids.size());
            for (String id : ids) {
                respuestas.add(tuberia.hgetAll("persona:" + id));
            }
            tuberia.sync();

            List<Persona> personas = new ArrayList<>(ids.size());
            for (int i = 0; i < ids.size(); i++) {
                Map<String, String> registro = respuestas.get(i).get();
                if (registro == null || registro.isEmpty()) {
                    continue; // el índice quedó apuntando a una persona borrada a mano
                }
                personas.add(Persona.newBuilder()
                        .setId(Integer.parseInt(ids.get(i)))
                        .setNombre(registro.get("nombre"))
                        .setLegajo(Integer.parseInt(registro.get("legajo")))
                        .build());
            }
            return personas;
        } catch (BaseNoDisponible e) {
            throw e;
        } catch (Exception e) {
            throw new BaseNoDisponible(e);
        }
    }

    /**
     * Da de alta y devuelve el id, o null si el legajo ya estaba registrado.
     *
     * El id lo asigna la base (INCR), nunca la app: si lo calculara cada réplica
     * contando lo que ya hay, dos altas simultáneas se pisarían.
     */
    public Integer crear(String nombre, int legajo) {
        Object resultado;
        try (Jedis jedis = pool.getResource()) {
            resultado = jedis.eval(LUA_ALTA_PERSONA,
                    List.of("legajo:" + legajo),
                    List.of(nombre, String.valueOf(legajo)));
        } catch (Exception e) {
            throw new BaseNoDisponible(e);
        }
        long id = ((Number) resultado).longValue();
        return id == -1 ? null : (int) id;
    }
}

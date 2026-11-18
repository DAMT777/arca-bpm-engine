package com.arca;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Cliente REST de arca-servicios (registro de negocio y Score Engine). Lo usan los JavaDelegates de C10, C3 y G3
 * y el portal. Si el servicio rechaza la petición o no responde, lanza {@link ServicioException} con el motivo,
 * que en el motor queda como incidente y en las vistas como un mensaje de error.
 */
@Component
public class ServiciosArca {
    private static final ParameterizedTypeReference<Map<String, Object>> MAPA = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LISTA = new ParameterizedTypeReference<>() { };

    private final RestClient http;
    private final String base;

    public ServiciosArca(@Value("${arca.servicios-url}") String base, RestClient.Builder builder) {
        this.base = base.replaceAll("/+$", "");
        this.http = builder.build();
    }

    /**
     * Arma una ruta con sus variables codificadas, por ejemplo ruta("/api/firmas?email={e}", email).
     * Los valores se codifican en forma estricta: un «&» o un «+» dentro de un nombre no rompe la consulta.
     */
    public static String ruta(String plantilla, Object... variables) {
        return UriComponentsBuilder.fromUriString(plantilla).encode().buildAndExpand(variables).toUriString();
    }

    /** Arma el cuerpo JSON de una petición; acepta valores nulos, a diferencia de Map.of. */
    public static Map<String, Object> datos(Object... claveValor) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < claveValor.length; i += 2) {
            m.put((String) claveValor[i], claveValor[i + 1]);
        }
        return m;
    }

    public Map<String, Object> get(String ruta) {
        return llamar(ruta, () -> http.get().uri(uri(ruta)).retrieve().body(MAPA));
    }

    public List<Map<String, Object>> lista(String ruta) {
        return llamar(ruta, () -> http.get().uri(uri(ruta)).retrieve().body(LISTA));
    }

    public Map<String, Object> post(String ruta, Object cuerpo) {
        return llamar(ruta, () -> http.post().uri(uri(ruta)).contentType(MediaType.APPLICATION_JSON)
                .body(cuerpo == null ? Map.of() : cuerpo).retrieve().body(MAPA));
    }

    public Map<String, Object> put(String ruta, Object cuerpo) {
        return llamar(ruta, () -> http.put().uri(uri(ruta)).contentType(MediaType.APPLICATION_JSON)
                .body(cuerpo).retrieve().body(MAPA));
    }

    private URI uri(String ruta) {
        return URI.create(base + ruta);
    }

    private <T> T llamar(String ruta, Supplier<T> peticion) {
        try {
            return peticion.get();
        } catch (RestClientResponseException e) {
            String detalle = e.getResponseBodyAsString();
            int i = detalle.indexOf("\"error\":\"");
            String mensaje = i >= 0 ? detalle.substring(i + 9, detalle.indexOf('"', i + 9)) : e.getStatusText();
            throw new ServicioException(e.getStatusCode().value(), "arca-servicios rechazó " + ruta + ": " + mensaje);
        } catch (ResourceAccessException e) {
            throw new ServicioException(503, "arca-servicios no responde en " + base + ". Arráncalo antes que el motor.");
        }
    }

    /** Error al invocar arca-servicios, con el código HTTP que conviene devolver a las vistas. */
    public static class ServicioException extends RuntimeException {
        private final int estado;

        public ServicioException(int estado, String mensaje) {
            super(mensaje);
            this.estado = estado;
        }

        public int getEstado() {
            return estado;
        }
    }
}

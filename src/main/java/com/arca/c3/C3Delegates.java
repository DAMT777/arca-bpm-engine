package com.arca.c3;

import com.arca.ServiciosArca;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

import static com.arca.ServiciosArca.datos;
import static com.arca.ServiciosArca.ruta;
import static com.arca.Variables.numero;
import static com.arca.Variables.siNo;
import static com.arca.Variables.texto;

/**
 * C3 Suscribir la solicitud y sus procesos invocados C1 Calcular el Pre-Score y C3.1 Notificar la decisión.
 * Responsable: Jesus Gregorio Delgado.
 * El Pre-Score, el puntaje compuesto, la cotización y el plan de mejora los calcula el Score Engine de
 * arca-servicios, que además guarda la evaluación. El proceso usa esos resultados en la DMN y en sus compuertas.
 */
@Configuration
public class C3Delegates {
    private static final Logger LOG = LoggerFactory.getLogger(C3Delegates.class);
    private static final List<String> HALLAZGOS = List.of("restriccionCapa1", "reclamoPagado", "incidenteIA", "sinEOVigente");

    // ---------- C1 Calcular el Pre-Score (Capa 1). En la plataforma, OpenAI lee la web y Apify consulta el colegio y Avvo.
    /** Identifica la firma por su dominio en el registro de firmas de arca-servicios. */
    @Bean
    public JavaDelegate c1EscanearWeb(ServiciosArca servicios) {
        return ex -> {
            String dominio = texto(ex, "dominio", null);
            List<Map<String, Object>> firmas = servicios.lista(ruta("/api/firmas?dominio={d}", dominio));
            boolean registrada = !firmas.isEmpty();
            Object identificada = registrada ? firmas.get(0).get("nombre") : dominio.split("\\.")[0];
            LOG.info("[C1] Web de {} escaneada: {}", dominio, registrada ? "firma registrada " + identificada : "firma sin historial");
            ex.setVariable("firmaIdentificada", identificada);
            ex.setVariable("firmaRegistrada", registrada);
        };
    }

    /** Búsqueda de abogados en el colegio y en Avvo (Apify). Simulada: usa el número declarado por la firma. */
    @Bean
    public JavaDelegate c1BuscarAbogados() {
        return ex -> {
            long abogados = Math.round(numero(ex, "abogados", 5));
            LOG.info("[C1] Apify: {} abogados encontrados en el colegio y en Avvo", abogados);
            ex.setVariable("abogados", abogados);
        };
    }

    /** Pide al Score Engine el Pre-Score de 0 a 100 y la restricción disciplinaria de la firma. */
    @Bean
    public JavaDelegate c1CalcularPrescore(ServiciosArca servicios) {
        return ex -> {
            Map<String, Object> r = servicios.post("/api/score/pre-score",
                    datos("dominio", texto(ex, "dominio", null), "abogados", ex.getVariable("abogados")));
            double preScore = ((Number) r.get("preScore")).doubleValue();
            boolean restriccion = Boolean.TRUE.equals(r.get("restriccion"));
            LOG.info("[C1] Score Engine: Pre-Score {}{}", preScore, restriccion ? " con restricción (antecedente disciplinario)" : "");
            ex.setVariable("puntajeCapa1", preScore);
            ex.setVariable("restriccionCapa1", restriccion);
            ex.setVariable("senalesCapa1", r.get("senales"));
        };
    }

    // ---------- C3
    /** Arma el cuestionario (8 a 12 preguntas según los hallazgos) y registra la evaluación en arca-servicios. */
    @Bean
    public JavaDelegate c3PresentarCuestionario(ServiciosArca servicios) {
        return ex -> {
            servicios.post("/api/evaluaciones", datos(
                    "clave", ex.getProcessBusinessKey(),
                    "processInstanceId", ex.getProcessInstanceId(),
                    "firma", ex.getVariable("firma"),
                    "dominio", ex.getVariable("dominio"),
                    "abogados", Math.round(numero(ex, "abogados", 5)),
                    "preScore", ex.getVariable("puntajeCapa1")));
            long preguntas = Math.min(12, 8 + contarHallazgos(ex));
            LOG.info("[C3] Evaluación {} registrada; cuestionario de {} preguntas presentado", ex.getProcessBusinessKey(), preguntas);
            ex.setVariable("numeroPreguntas", preguntas);
        };
    }

    /** El Score Engine calcula el puntaje compuesto (30 % Capa 1, 70 % cuestionario) y si hay revisión forzada. */
    @Bean
    public JavaDelegate c3CalcularScore(ServiciosArca servicios) {
        return ex -> {
            Map<String, Object> r = servicios.post("/api/score/puntaje", datos(
                    "clave", ex.getProcessBusinessKey(),
                    "puntajeCapa1", numero(ex, "puntajeCapa1", 60),
                    "puntajeCapa2", numero(ex, "puntajeCapa2", 60),
                    "reclamoPagado", siNo(ex, "reclamoPagado"),
                    "incidenteIA", siNo(ex, "incidenteIA"),
                    "sinEOVigente", siNo(ex, "sinEOVigente"),
                    "restriccionCapa1", siNo(ex, "restriccionCapa1")));
            long puntaje = ((Number) r.get("puntaje")).longValue();
            boolean forzada = Boolean.TRUE.equals(r.get("revisionForzada"));
            LOG.info("[C3] Score Engine: puntaje {}; revisión forzada: {}", puntaje, forzada);
            ex.setVariable("puntaje", puntaje);
            ex.setVariable("revisionForzada", forzada);
        };
    }

    @Bean
    public JavaDelegate c3MostrarResultados() {
        return ex -> LOG.info("[C3] Resultados y plan de acción mostrados: puntaje {}", ex.getVariable("puntaje"));
    }

    /** El Score Engine cotiza la prima anual según el tier y guarda la cotización en la evaluación. */
    @Bean
    public JavaDelegate c3CalcularCotizacion(ServiciosArca servicios) {
        return ex -> {
            Map<String, Object> r = servicios.post("/api/score/cotizacion", datos(
                    "clave", ex.getProcessBusinessKey(),
                    "abogados", Math.round(numero(ex, "abogados", 5)),
                    "tier", ex.getVariable("tier"),
                    "limiteSolicitado", ex.getVariable("limiteSolicitado")));
            long prima = ((Number) r.get("primaAnual")).longValue();
            LOG.info("[C3] Score Engine: prima anual USD {}, límite {}", prima, r.get("limiteOfrecido"));
            ex.setVariable("primaAnual", prima);
            ex.setVariable("limiteOfrecido", r.get("limiteOfrecido"));
        };
    }

    @Bean
    public JavaDelegate c3GenerarPlanMejora(ServiciosArca servicios) {
        return ex -> {
            Map<String, Object> r = servicios.post("/api/score/plan-mejora", datos("clave", ex.getProcessBusinessKey()));
            LOG.info("[C3] Plan de mejora generado; nueva evaluación el {}", r.get("fechaReevaluacion"));
            ex.setVariable("fechaReevaluacion", r.get("fechaReevaluacion"));
        };
    }

    /** Registra la decisión final en el expediente de la evaluación. */
    @Bean
    public JavaDelegate c3RegistrarDecision(ServiciosArca servicios) {
        return ex -> {
            String decision = texto(ex, "decision", null);
            Map<String, Object> r = servicios.put(ruta("/api/evaluaciones/{clave}", ex.getProcessBusinessKey()), datos(
                    "estado", "ACCEPT".equals(decision) ? "ACEPTADA" : "DECLINADA",
                    "tier", ex.getVariable("tier"),
                    "decision", decision));
            LOG.info("[C3] Decisión {} registrada en el expediente {}", decision, r.get("clave"));
            ex.setVariable("fechaDecision", String.valueOf(r.get("actualizadaEn")));
        };
    }

    /** Proceso invocado C3.1 Notificar la decisión al canal (Call Activity). */
    @Bean
    public JavaDelegate c3EnviarNotificacion() {
        return ex -> {
            Object prima = ex.getVariable("primaAnual");
            LOG.info("[C3.1] Decisión {} (tier {}){} notificada al canal", ex.getVariable("decision"), ex.getVariable("tier"),
                    prima != null ? ", prima USD " + prima : "");
            ex.setVariable("notificacionEnviada", true);
        };
    }

    private static long contarHallazgos(DelegateExecution ex) {
        return HALLAZGOS.stream().filter(k -> siNo(ex, k)).count();
    }
}

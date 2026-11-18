package com.arca.web;

import com.arca.ServiciosArca;
import org.camunda.bpm.engine.HistoryService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static com.arca.ServiciosArca.ruta;

/**
 * Portal de la firma: registro e ingreso (la firma queda guardada en arca-servicios), sus pólizas y el historial
 * de sus avisos de siniestro (C10) y evaluaciones (C3), con el estado que da el motor.
 */
@RestController
@RequestMapping("/api/portal")
public class PortalController {
    private final HistoryService historyService;
    private final Instancias instancias;
    private final ServiciosArca servicios;

    public PortalController(HistoryService historyService, Instancias instancias, ServiciosArca servicios) {
        this.historyService = historyService;
        this.instancias = instancias;
        this.servicios = servicios;
    }

    public record Registro(String nombre, String dominio, String email, Integer abogados) {
    }

    /** Alta de la firma. arca-servicios valida los datos y rechaza nombres, dominios o emails repetidos. */
    @PostMapping("/firmas")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> registrar(@RequestBody Registro r) {
        return servicios.post("/api/firmas", r);
    }

    /** Ingreso de una firma ya registrada por su email. */
    @GetMapping("/firmas")
    public Map<String, Object> ingresar(@RequestParam String email) {
        if (email.isBlank()) {
            throw new IllegalArgumentException("Escribe el email con el que registraste la firma");
        }
        List<Map<String, Object>> firmas = servicios.lista(ruta("/api/firmas?email={e}", email.trim()));
        if (firmas.isEmpty()) {
            throw new NoSuchElementException("No hay ninguna firma registrada con " + email.trim() + ". Regístrala primero.");
        }
        return firmas.get(0);
    }

    @GetMapping("/firmas/{id}/polizas")
    public List<Map<String, Object>> polizas(@PathVariable long id) {
        return servicios.lista(ruta("/api/firmas/{id}/polizas", id));
    }

    @GetMapping("/solicitudes")
    public List<Map<String, Object>> solicitudes(@RequestParam String firma) {
        if (firma.isBlank()) {
            throw new IllegalArgumentException("Indica el nombre de la firma");
        }
        return historyService.createHistoricProcessInstanceQuery()
                .processDefinitionKeyIn("Process_C10_RegistrarAvisoSiniestro", "Process_C3_SuscribirSolicitud")
                .matchVariableValuesIgnoreCase()
                .variableValueEquals("firma", firma.trim())
                .orderByProcessInstanceStartTime().desc()
                .list().stream()
                .map(pi -> {
                    Map<String, Object> r = instancias.resumen(pi);
                    r.put("esperaRespuestas", pi.getEndTime() == null
                            && instancias.esperaMensaje(pi.getId(), "RespuestasCuestionarioRecibidas"));
                    return r;
                })
                .toList();
    }
}

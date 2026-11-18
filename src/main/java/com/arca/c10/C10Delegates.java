package com.arca.c10;

import com.arca.ServiciosArca;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static com.arca.ServiciosArca.datos;
import static com.arca.ServiciosArca.ruta;
import static com.arca.Variables.fecha;
import static com.arca.Variables.texto;

/**
 * C10 Registrar el aviso de siniestro y C10.1 Comunicar a la firma.
 * Responsable: Diego Alejandro Machado Tovar.
 * Cada bean atiende la tarea de servicio cuyo delegateExpression lleva su nombre. Las tareas que tocan datos
 * del negocio llaman a arca-servicios: registro del aviso, vigencia de la póliza, expediente y cierre.
 */
@Configuration
public class C10Delegates {
    private static final Logger LOG = LoggerFactory.getLogger(C10Delegates.class);

    private static String aviso(DelegateExecution ex) {
        return ruta("/api/avisos/{clave}", ex.getProcessBusinessKey());
    }

    /** Envía el acuse de recibo y registra el aviso en arca-servicios, ligado a esta instancia. */
    @Bean
    public JavaDelegate c10AcusarRecibo(ServiciosArca servicios) {
        return ex -> {
            LocalDate hecho = fecha(ex, "fechaHecho");
            servicios.post("/api/avisos", datos(
                    "clave", ex.getProcessBusinessKey(),
                    "processInstanceId", ex.getProcessInstanceId(),
                    "firma", texto(ex, "firma", null),
                    "numeroPoliza", texto(ex, "numeroPoliza", null),
                    "fechaHecho", hecho == null ? null : hecho.toString(),
                    "descripcion", texto(ex, "descripcionHecho", null)));
            LOG.info("[C10] Aviso {} registrado; acuse de recibo enviado a {}", ex.getProcessBusinessKey(), ex.getVariable("firma"));
            ex.setVariable("fechaAcuse", Instant.now().toString());
        };
    }

    /** Consulta en el registro de pólizas si la póliza es de la firma y estaba vigente a la fecha del hecho. */
    @Bean
    public JavaDelegate c10VerificarVigencia(ServiciosArca servicios) {
        return ex -> {
            Map<String, Object> r = servicios.get(ruta("/api/polizas/{numero}/vigencia?fecha={fecha}&firma={firma}",
                    texto(ex, "numeroPoliza", ""), fecha(ex, "fechaHecho"), texto(ex, "firma", "")));
            boolean vigente = Boolean.TRUE.equals(r.get("vigente"));
            LOG.info("[C10] Póliza {} vigente a la fecha del hecho: {} ({})", r.get("numero"), vigente, r.get("motivo"));
            ex.setVariable("polizaVigente", vigente);
            ex.setVariable("motivoVigencia", r.get("motivo"));
        };
    }

    /** Recupera del registro los límites, el deducible y las exclusiones de la póliza. */
    @Bean
    public JavaDelegate c10RecuperarPoliza(ServiciosArca servicios) {
        return ex -> {
            Map<String, Object> p = servicios.get(ruta("/api/polizas/{numero}/vigencia?fecha={fecha}",
                    texto(ex, "numeroPoliza", ""), fecha(ex, "fechaHecho")));
            ex.setVariable("limiteCobertura", ((Number) p.get("limiteCobertura")).doubleValue());
            ex.setVariable("deducible", ((Number) p.get("deducible")).doubleValue());
            ex.setVariable("exclusionesPoliza", p.get("exclusiones"));
            ex.setVariable("inicioVigencia", p.get("inicioVigencia"));
            ex.setVariable("finVigencia", p.get("finVigencia"));
            LOG.info("[C10] Póliza {} recuperada: límite {}, deducible {}", p.get("numero"), p.get("limiteCobertura"), p.get("deducible"));
        };
    }

    /** Guarda la revisión y la preverificación y abre el expediente con un número consecutivo. */
    @Bean
    public JavaDelegate c10AbrirExpediente(ServiciosArca servicios) {
        return ex -> {
            servicios.put(aviso(ex), datos(
                    "estado", "PREVERIFICADO",
                    "montoReclamado", ex.getVariable("montoReclamado"),
                    "resultado", ex.getVariable("resultadoPreverificacion"),
                    "fundamento", ex.getVariable("fundamentoPreverificacion")));
            Map<String, Object> r = servicios.post(aviso(ex) + "/expediente", null);
            LOG.info("[C10] Expediente abierto: {}", r.get("numeroExpediente"));
            ex.setVariable("numeroExpediente", r.get("numeroExpediente"));
        };
    }

    @Bean
    public JavaDelegate c10RegistrarSinCobertura(ServiciosArca servicios) {
        return ex -> {
            Object fundamento = ex.getVariable("fundamentoPreverificacion");
            servicios.put(aviso(ex), datos(
                    "estado", "CERRADO_SIN_COBERTURA",
                    "montoReclamado", ex.getVariable("montoReclamado"),
                    "resultado", "SIN_COBERTURA",
                    "fundamento", fundamento != null ? fundamento : ex.getVariable("motivoVigencia")));
            LOG.info("[C10] Aviso {} registrado como sin cobertura", ex.getProcessBusinessKey());
            ex.setVariable("estadoAviso", "CERRADO_SIN_COBERTURA");
        };
    }

    /** Ruta del temporizador de 24 h: la preverificación sigue abierta y se informa a la firma. */
    @Bean
    public JavaDelegate c10InformarEstado() {
        return ex -> {
            LOG.info("[C10] Han pasado 24 h sin preverificación: se informa a la firma que el aviso sigue en revisión");
            ex.setVariable("ultimoAvisoEstado", Instant.now().toString());
        };
    }

    /** Proceso invocado C10.1 Comunicar a la firma (Call Activity). Deja el estado final del aviso. */
    @Bean
    public JavaDelegate c10EnviarComunicacion(ServiciosArca servicios) {
        return ex -> {
            String tipo = texto(ex, "tipoComunicacion", "SIN_TIPO");
            boolean preverificacion = "PREVERIFICACION".equals(tipo);
            servicios.put(aviso(ex), datos("estado", preverificacion ? "SINIESTRO_REGISTRADO" : "CERRADO_SIN_COBERTURA"));
            String detalle = preverificacion
                    ? "resultado " + texto(ex, "resultadoPreverificacion", "-") + ", expediente " + texto(ex, "numeroExpediente", "-")
                    : "aviso cerrado sin cobertura";
            LOG.info("[C10.1] Comunicación {} enviada a la firma: {}", tipo, detalle);
            ex.setVariable("comunicacionEnviada", true);
        };
    }
}

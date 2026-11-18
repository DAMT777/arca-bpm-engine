package com.arca.g3;

import com.arca.ServiciosArca;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static com.arca.ServiciosArca.datos;
import static com.arca.ServiciosArca.ruta;
import static com.arca.Variables.texto;

/**
 * G3 Publicar una versión del modelo de score y su proceso invocado S2 Desplegar cambios en plataforma.
 * Responsable: Johan Sebastian Forero Agudelo.
 * La versión se registra en arca-servicios, que ejecuta el backtesting (simulado) y lleva su estado y vigencia.
 * La respuesta del carrier la da el rol Carrier desde el panel interno.
 */
@Configuration
public class G3Delegates {
    private static final Logger LOG = LoggerFactory.getLogger(G3Delegates.class);

    private static String version(DelegateExecution ex) {
        return ruta("/api/versiones/{clave}", ex.getProcessBusinessKey());
    }

    /** Registra la versión candidata y pide su backtesting y prueba de sesgo. Variable opcional simularValidacion = ok | falla. */
    @Bean
    public JavaDelegate g3ValidarVersion(ServiciosArca servicios) {
        return ex -> {
            servicios.post("/api/versiones", datos(
                    "clave", ex.getProcessBusinessKey(),
                    "processInstanceId", ex.getProcessInstanceId(),
                    "version", texto(ex, "versionCandidata", null),
                    "motivo", ex.getVariable("motivo")));
            String escenario = texto(ex, "simularValidacion", "ok");
            Map<String, Object> r = servicios.post(version(ex) + "/backtesting", datos("escenario", escenario));
            LOG.info("[G3] Backtesting de {} ({}): AUC {}, desviación del loss ratio {}, impacto dispar {}",
                    ex.getVariable("versionCandidata"), escenario, r.get("auc"), r.get("desviacionLossRatio"), r.get("impactoDisparejo"));
            ex.setVariable("auc", ((Number) r.get("auc")).doubleValue());
            ex.setVariable("desviacionLossRatio", ((Number) r.get("desviacionLossRatio")).doubleValue());
            ex.setVariable("impactoDisparejo", ((Number) r.get("impactoDisparejo")).doubleValue());
            // Si la validación falla, la siguiente vuelta simula una corrección exitosa
            ex.setVariable("simularValidacion", "ok");
        };
    }

    @Bean
    public JavaDelegate g3SometerCarrier(ServiciosArca servicios) {
        return ex -> {
            servicios.put(version(ex), datos("estado", "SOMETIDA_AL_CARRIER"));
            LOG.info("[G3] Versión {} enviada al carrier; queda esperando su respuesta (plazo de 15 días)",
                    texto(ex, "versionCandidata", "sin-identificador"));
        };
    }

    /** Publica la versión como vigente; la anterior queda reemplazada. */
    @Bean
    public JavaDelegate g3RegistrarVigencia(ServiciosArca servicios) {
        return ex -> {
            Map<String, Object> r = servicios.post(version(ex) + "/vigencia", null);
            LOG.info("[G3] Versión {} vigente desde {}; reemplaza a {}", r.get("version"), r.get("vigenteDesde"), r.get("versionAnterior"));
            ex.setVariable("versionVigente", r.get("version"));
            ex.setVariable("fechaVigencia", String.valueOf(r.get("vigenteDesde")));
            ex.setVariable("versionAnterior", r.get("versionAnterior"));
        };
    }

    /** Evento final de mensaje VersionModeloPublicada, que avisa a C1, C3 y C7. */
    @Bean
    public JavaDelegate g3PublicarVersion() {
        return ex -> LOG.info("[G3] Mensaje VersionModeloPublicada emitido hacia C1, C3 y C7");
    }

    // ---------- S2 Desplegar cambios en plataforma
    @Bean
    public JavaDelegate s2DesplegarCambio(ServiciosArca servicios) {
        return ex -> {
            servicios.put(version(ex), datos("estado", "EN_DESPLIEGUE"));
            LOG.info("[S2] Desplegando la versión {} en la plataforma", ex.getVariable("versionCandidata"));
        };
    }

    /** Pruebas de humo. Variable opcional simularDespliegue = ok | falla. */
    @Bean
    public JavaDelegate s2PruebasHumo() {
        return ex -> {
            boolean saludable = !"falla".equals(texto(ex, "simularDespliegue", "ok"));
            LOG.info("[S2] Pruebas de humo: {}", saludable ? "OK" : "FALLARON");
            ex.setVariable("despliegueSaludable", saludable);
        };
    }

    @Bean
    public JavaDelegate s2RevertirDespliegue(ServiciosArca servicios) {
        return ex -> {
            servicios.put(version(ex), datos("estado", "DESPLIEGUE_REVERTIDO"));
            LOG.info("[S2] Revirtiendo al estado anterior");
            ex.setVariable("despliegueSaludable", false);
        };
    }
}

package com.arca;

import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Arranca el motor con H2 en memoria y comprueba el despliegue y la integración de C10 con arca-servicios.
 * arca-servicios se reemplaza por un doble de prueba: así se verifica qué se le pide y cómo usa el proceso la respuesta.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:arca;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "camunda.bpm.database.type=h2",
        "camunda.bpm.admin-user.password=prueba",
        "arca.demo-password=prueba",
        "spring.config.import=",
})
class ArcaApplicationTest {
    @Autowired
    RepositoryService repositoryService;
    @Autowired
    RuntimeService runtimeService;
    @Autowired
    TaskService taskService;
    @Autowired
    HistoryService historyService;
    @MockitoBean
    ServiciosArca servicios;

    @BeforeEach
    void respuestasPorDefecto() {
        when(servicios.post(anyString(), any())).thenReturn(Map.of());
        when(servicios.put(anyString(), any())).thenReturn(Map.of());
    }

    private ProcessInstance aviso(String clave) {
        return runtimeService.createMessageCorrelation("AvisoReclamacion")
                .processInstanceBusinessKey(clave)
                .setVariables(Map.of("firma", "Test LLP", "numeroPoliza", "POL-1", "fechaHecho", "2026-10-01",
                        "descripcionHecho", "Prueba"))
                .correlateStartMessage();
    }

    @Test
    void despliegaLosSieteProcesosYLasTresDecisiones() {
        assertThat(repositoryService.createProcessDefinitionQuery().latestVersion().count()).isEqualTo(7);
        assertThat(repositoryService.createDecisionDefinitionQuery().latestVersion().count()).isEqualTo(3);
    }

    @Test
    void c10UsaLaVigenciaQueRespondeElRegistroDePolizas() {
        when(servicios.get(contains("/vigencia"))).thenReturn(Map.of("numero", "POL-1", "vigente", false,
                "motivo", "El hecho ocurrió fuera de la vigencia de la póliza"));
        ProcessInstance pi = aviso("AV-TEST");

        verify(servicios).post(eq("/api/avisos"), any());
        assertThat(historyService.createHistoricProcessInstanceQuery().processInstanceId(pi.getId()).singleResult().getEndActivityId())
                .isEqualTo("EndEvent_C10_AvisoSinCobertura");
        // Registrar sin cobertura y, en C10.1, comunicarlo a la firma: dos actualizaciones del aviso
        verify(servicios, times(2)).put(eq("/api/avisos/AV-TEST"), any());
    }

    @Test
    void c10ConPolizaVigenteCreaLaTareaDelJefeConLosLimitesRecuperados() {
        when(servicios.get(contains("/vigencia"))).thenReturn(Map.of("numero", "POL-1", "vigente", true, "motivo", "Vigente",
                "limiteCobertura", 1000000, "deducible", 10000, "exclusiones", "Actos dolosos"));
        ProcessInstance pi = aviso("AV-TEST-2");

        var tarea = taskService.createTaskQuery().processInstanceId(pi.getId()).taskCandidateGroup("jefes-siniestros").singleResult();
        assertThat(tarea.getTaskDefinitionKey()).isEqualTo("Task_C10_RevisarAviso");
        assertThat(runtimeService.getVariable(pi.getId(), "limiteCobertura")).isEqualTo(1000000.0);
    }

    @Test
    void siArcaServiciosNoRespondeElAvisoNoSeInicia() {
        when(servicios.post(eq("/api/avisos"), any())).thenThrow(new ServiciosArca.ServicioException(503, "arca-servicios no responde"));
        assertThatThrownBy(() -> aviso("AV-TEST-3")).hasMessageContaining("arca-servicios no responde");
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceBusinessKey("AV-TEST-3").count()).isZero();
    }
}

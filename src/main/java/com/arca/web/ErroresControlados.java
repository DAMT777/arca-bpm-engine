package com.arca.web;

import com.arca.ServiciosArca;
import org.camunda.bpm.engine.MismatchingMessageCorrelationException;
import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.exception.NotFoundException;
import org.camunda.bpm.engine.exception.NullValueException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Convierte los errores del motor y de validación en respuestas JSON con un mensaje legible para las vistas. */
@RestControllerAdvice(basePackages = "com.arca")
public class ErroresControlados {
    private static final Logger LOG = LoggerFactory.getLogger(ErroresControlados.class);

    /** arca-servicios rechazó la petición (se respeta su 4xx) o no respondió (503). */
    @ExceptionHandler(ServiciosArca.ServicioException.class)
    public ResponseEntity<Map<String, String>> servicio(ServiciosArca.ServicioException e) {
        LOG.warn(e.getMessage());
        int estado = e.getEstado() >= 400 && e.getEstado() < 600 ? e.getEstado() : 502;
        return respuesta(HttpStatus.valueOf(estado), e.getMessage());
    }

    @ExceptionHandler(java.util.NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> sinResultado(java.util.NoSuchElementException e) {
        return respuesta(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> datosInvalidos(IllegalArgumentException e) {
        return respuesta(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler({IllegalStateException.class, MismatchingMessageCorrelationException.class})
    public ResponseEntity<Map<String, String>> conflicto(RuntimeException e) {
        return respuesta(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler({NotFoundException.class, NullValueException.class})
    public ResponseEntity<Map<String, String>> noEncontrado(ProcessEngineException e) {
        return respuesta(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ProcessEngineException.class)
    public ResponseEntity<Map<String, String>> motor(ProcessEngineException e) {
        for (Throwable c = e.getCause(); c != null; c = c.getCause()) {
            if (c instanceof ServiciosArca.ServicioException s) {
                return servicio(s);
            }
        }
        LOG.warn("Error del motor: {}", e.getMessage());
        return respuesta(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    private static ResponseEntity<Map<String, String>> respuesta(HttpStatus estado, String mensaje) {
        return ResponseEntity.status(estado).body(Map.of("error", mensaje == null ? estado.getReasonPhrase() : mensaje));
    }
}

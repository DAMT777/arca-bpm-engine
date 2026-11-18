# ARCA · Camunda 7 embebido en Spring Boot

Los tres procesos de ARCA corren en un motor Camunda 7.24.0 embebido en una aplicación Spring Boot 3.5.5 con Java 21 y una base Postgres propia. Al arrancar, la aplicación despliega los procesos y sirve dos vistas: el portal de la firma y el panel interno por rol. Cockpit, Tasklist y Admin quedan en `/camunda/app/`.

Este repositorio es un componente de [ARCA · hub del proyecto](https://github.com/DAMT777/arca-hub). El hub tiene la guía general, la arquitectura y los enlaces a los demás repositorios.

| Proceso | Responsable | Código Java | Modelos |
|---|---|---|---|
| C10 Registrar el aviso de siniestro | Diego Alejandro Machado Tovar | `src/main/java/com/arca/c10` | `src/main/resources/procesos/c10` |
| C3 Suscribir la solicitud | Jesus Gregorio Delgado | `src/main/java/com/arca/c3` | `src/main/resources/procesos/c3` |
| G3 Publicar una versión del modelo de score | Johan Sebastian Forero Agudelo | `src/main/java/com/arca/g3` | `src/main/resources/procesos/g3` |

`com.arca.web` es común: la bandeja de tareas, el portal (registro, ingreso e historial), los usuarios de demo y el manejo de errores. `com.arca.ServiciosArca` es el cliente REST de [arca-servicios](https://github.com/DAMT777/arca-servicios), el servicio de negocio que invocan los delegates.

## Integración con arca-servicios

Las tareas de servicio no simulan los datos del negocio: los piden a **arca-servicios** por REST, y el proceso usa la respuesta para decidir su camino.

| Proceso | Tarea de servicio | Llamada a arca-servicios | Cómo usa el proceso la respuesta |
|---|---|---|---|
| C10 | Enviar el acuse de recibo | `POST /api/avisos` | Registra el aviso con su clave y su instancia |
| C10 | Verificar la vigencia de la póliza | `GET /api/polizas/{numero}/vigencia` | `polizaVigente` decide la compuerta «¿Póliza vigente?» |
| C10 | Recuperar póliza, límites y exclusiones | `GET /api/polizas/{numero}/vigencia` | Muestra el límite, el deducible y las exclusiones en la revisión del Jefe |
| C10 | Abrir el expediente | `PUT /api/avisos/{clave}` y `POST /api/avisos/{clave}/expediente` | Guarda la preverificación y devuelve `numeroExpediente` |
| C10 | Registrar sin cobertura / C10.1 Comunicar | `PUT /api/avisos/{clave}` | Deja el estado final del aviso |
| C3 (C1) | Escanear la web / Calcular el Pre-Score | `GET /api/firmas?dominio=`, `POST /api/score/pre-score` | `puntajeCapa1` y `restriccionCapa1` entran al puntaje y fuerzan la revisión |
| C3 | Presentar el cuestionario | `POST /api/evaluaciones` | Registra la evaluación con su instancia |
| C3 | Calcular el puntaje compuesto | `POST /api/score/puntaje` | `puntaje` y `revisionForzada` son las entradas de la DMN de ruta |
| C3 | Cotización / Plan de mejora / Registrar decisión | `POST /api/score/cotizacion`, `POST /api/score/plan-mejora`, `PUT /api/evaluaciones/{clave}` | Prima y límite, o fecha de reevaluación; estado final de la evaluación |
| G3 | Validar la versión | `POST /api/versiones`, `POST /api/versiones/{clave}/backtesting` | `auc`, `desviacionLossRatio` e `impactoDisparejo` entran a la DMN de validación |
| G3 | Someter al carrier / S2 | `PUT /api/versiones/{clave}` | Estado de la versión (sometida, en despliegue, revertida) |
| G3 | Registrar la vigencia | `POST /api/versiones/{clave}/vigencia` | La versión queda vigente y la anterior, reemplazada |

Si arca-servicios rechaza una petición o no responde, `ServiciosArca` lanza un error con el motivo. Cuando lo inició una vista, la vista recibe el mensaje y el proceso no avanza. Cuando ocurre en una tarea del motor, queda un incidente en Cockpit.

## Vistas (React)

Las vistas están hechas con **React 18** sin paso de compilación. Usan [htm](https://github.com/developit/htm), que permite escribir el marcado con plantillas de JavaScript en lugar de JSX. Spring Boot las sirve como archivos estáticos desde `src/main/resources/static/`:

| Archivo | Contenido |
|---|---|
| `vendor/` | React, ReactDOM y htm descargados de npm. Están incluidos para que la demo funcione sin internet; ver `vendor/LEEME.txt`. |
| `arca.css` | Estilos: tema claro fijo (no cambia con el modo oscuro del sistema), tiers de FORTRESS a CRITICAL, tarjetas, pestañas, modal y diseño adaptable a celular. |
| `arca.js` | Componentes comunes: barra superior, píldoras de estado, medidor de puntaje, avisos flotantes, modal y el formulario Camunda (`.form`) dibujado con React. |
| `js/inicio.js`, `js/portal.js`, `js/panel.js` | Una página React por vista. |

- **Portal de la firma:**
  - **Registro e ingreso:** la firma se registra una vez (nombre, dominio, email y abogados) y luego ingresa con su email. Sus datos quedan en arca-servicios.
  - Pestañas «Mis solicitudes», «Reportar siniestro» y «Evaluar mi firma».
  - Cada solicitud muestra su estado, y en C3 también el tier y un medidor del puntaje.
  - El cuestionario se abre en un modal con una barra de progreso.
  - Los datos se actualizan cada 5 segundos.
- **Panel interno:**
  - Menú lateral con los roles agrupados por proceso y el número de tareas de cada uno.
  - Indicadores de la bandeja y lista de tareas.
  - Detalle de la tarea con su contexto y el formulario Camunda.
  - Vistas del Actuario Jefe (nueva versión del modelo) y del Carrier, y la tabla de versiones del modelo.

## Qué cambia frente a la versión con Camunda Run

- Los BPMN, DMN y formularios son copias de las carpetas de cada proceso del repositorio [arca-procesos-camunda-run](https://github.com/yixx16/bpmn), dentro de su carpeta `arca/`. Esas carpetas originales y sus workers en JavaScript siguen igual y funcionan con Camunda Run.
- Cada External Task pasó a un **JavaDelegate** (`camunda:delegateExpression="${c10AcusarRecibo}"`, etc.). Los que tocan datos del negocio llaman a arca-servicios (ver la sección anterior). Si falta un dato o el servicio rechaza la petición, el delegate lanza un error con un mensaje claro y el motor deja un incidente visible en Cockpit.
- La vigencia de la póliza ya no la informa la firma: se consulta en el registro de pólizas.
- Las tareas de usuario de C10 y C3 usan `camunda:formRef` con binding `deployment`, igual que G3, porque Spring Boot despliega los formularios con su ruta y no solo con el nombre del archivo.
- Lo que antes simulaba `demo.mjs` ahora se hace en las vistas: la firma envía el aviso, inicia la evaluación y responde el cuestionario, y el carrier aprueba o rechaza la versión.

## Cómo levantarlo

Requisitos: JDK 21 y Docker. **Antes, arranca [arca-servicios](https://github.com/DAMT777/arca-servicios)** (puerto 8091): el motor lo necesita para iniciar y avanzar los procesos.

1. Copia `.env.example` como `.env` y cambia las tres contraseñas. `.env` no se versiona. `ARCA_SERVICIOS_URL` indica dónde está arca-servicios (por defecto `http://localhost:8091`).
2. Levanta la base de datos:

   ```bash
   docker compose up -d
   ```

3. Arranca la aplicación desde esta carpeta. El wrapper descarga Maven la primera vez.

   ```bash
   ./mvnw spring-boot:run
   ```

   En Windows: `mvnw.cmd spring-boot:run`. La aplicación lee el mismo `.env`. El log muestra `ENGINE-08023 Deployment summary for process archive 'ARCA'` y `ProcessApplication 'ARCA' registered`.

| Qué | Dónde |
|---|---|
| Inicio | http://localhost:8090/ |
| Portal de la firma | http://localhost:8090/portal.html |
| Panel interno | http://localhost:8090/panel.html |
| Cockpit (monitoreo) | http://localhost:8090/camunda/app/cockpit/default/ con `CAMUNDA_ADMIN_USER` y `CAMUNDA_ADMIN_PASSWORD` |
| API REST del motor | http://localhost:8090/engine-rest |

Al arrancar se crean los grupos candidatos de los BPMN y un usuario de demo por grupo, con la contraseña `ARCA_DEMO_PASSWORD`: `jefe.siniestros`, `asesor.juridico`, `suscriptor`, `actuario`, `director.suscripcion`, `ingeniero.datos` y `comite.modelo`. Con ellos también se puede entrar a Tasklist.

## Probar el flujo completo

**C10 · Portal → Jefe de Siniestros → Asesor Jurídico**
1. En el portal, ingresa con `riesgos@smithlee.com` (firma de ejemplo de arca-servicios) y llena **Reportar un siniestro** con la póliza `POL-2026-0042` y una fecha desde el 1 de marzo de 2026.
2. En el panel, con el rol **Jefe de Siniestros**, abre «Revisar el aviso». Con un monto de 250.000 o más, o con una exclusión dudosa, la DMN pide concepto jurídico.
3. Con el rol **Asesor Jurídico**, emite el concepto. Vuelve a **Jefe de Siniestros** y determina la preverificación.
4. En **Mis solicitudes** del portal se ve «Siniestro registrado» con el número de expediente, o «Aviso cerrado sin cobertura». Si el hecho es anterior a la vigencia, la póliza no existe o es de otra firma, el aviso se cierra sin cobertura de inmediato.

**C3 · Portal → Suscriptor, Actuario y Director**
1. En el portal, en la pestaña **Evaluar mi firma**, inicia la evaluación. El cuestionario se abre solo; si lo cierras, sigue disponible en **Mis solicitudes** con el botón **Responder cuestionario**.
2. Cada «sí» suma por igual al puntaje del cuestionario. El Pre-Score lo da el Score Engine: 60 para Smith & Lee y para firmas sin historial, 74 para Garcia Legal Group y 48 con restricción disciplinaria para Rivera & Cole, que siempre pasa a revisión humana. Con Pre-Score 60: 8 «sí» dan FORTRESS y se acepta sola, 4 «sí» dan GUARDED y va al **Suscriptor**, 1 «sí» da EXPOSED y va al **Actuario** y al **Director de Suscripción**, y 0 «sí» dan CRITICAL y se declina. Marcar un reclamo pagado, un incidente de IA o no tener E&O vigente fuerza la revisión del Suscriptor.
3. El portal muestra el tier, la decisión y la prima, o la fecha de reevaluación.

**G3 · Panel: Actuario Jefe → Ingeniero de Datos → Comité → Carrier**
1. Con el rol **Actuario**, usa **Nueva versión del modelo**. Los selectores de escenario permiten probar la validación fallida y el despliegue revertido.
2. Completa las tareas en orden: Actuario (consolidar), **Ingeniero de Datos** (calibrar), Actuario (documentar) y **Comité de Modelo** (decidir).
3. Con el rol **Carrier**, aprueba o rechaza. La tabla **Versiones del modelo** muestra el final: publicada, rechazada, no aprobada por el carrier o despliegue revertido.

Cockpit muestra cada instancia y por dónde pasó. Los temporizadores de 24 h y 15 días se adelantan con el script `pruebas/demo.mjs`, que está en el repositorio [arca-procesos-camunda-run](https://github.com/yixx16/bpmn). Necesita Node.js 18 o superior. Desde la carpeta `arca` de ese repositorio, o desde `arca-hub/arca-procesos-camunda-run/arca` si clonaste el hub, apuntado a este motor:

```bash
CAMUNDA_URL=http://localhost:8090/engine-rest node pruebas/demo.mjs vencer c3
```

Cambia `c3` por `c10` o `g3` según el proceso cuyo temporizador quieras disparar.

## Pruebas automáticas

```bash
./mvnw test
```

Arrancan el motor con H2 en memoria y con un doble de prueba de arca-servicios. Comprueban:
- que se despliegan 7 procesos y 3 decisiones;
- que C10 usa la vigencia que responde el registro de pólizas, tanto vigente como no vigente;
- que si arca-servicios no responde, el aviso no se inicia.

Los 25 casos de punta a punta, con el sistema completo en marcha, están en el hub: `pruebas/casos.mjs` y `docs/casos-de-prueba.md`.

## Advertencias de la demo

- El panel no pide login: el rol se elige en el menú lateral y la API comprueba que el usuario pertenezca al grupo candidato de la tarea. Cockpit, Tasklist y Admin sí piden usuario y contraseña.
- `/engine-rest` no tiene autenticación, igual que Camunda Run en la configuración del curso. No expongas el puerto fuera de tu equipo.

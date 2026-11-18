// ARCA · Portal de la firma (C10 avisos de siniestro y C3 evaluación de riesgo)
(function () {
  const { useState } = React;
  const { html, api, useDatos, guardado, guardar, fecha, fechaCorta, usd, hace, iniciales, tonoEstado,
    Icono, Topbar, Pildora, Tier, Medidor, Vacio, Avisos, avisar, Modal, Campo } = ARCA;

  const PREVERIFICACION = { CUBIERTO: "Cubierto", RESERVA: "Con reserva de derechos", SIN_COBERTURA: "Sin cobertura" };

  // ---------- Solicitudes
  function Solicitud({ s, responder }) {
    const v = s.variables;
    const esAviso = s.procesoKey === "Process_C10_RegistrarAvisoSiniestro";
    const tono = tonoEstado(s);
    return html`<article class="solicitud">
      <div class=${"solicitud-icono " + (esAviso ? "C10" : "C3")}><${Icono} n=${esAviso ? "alerta" : "grafica"} t=${22} /></div>
      <div>
        <h3>${esAviso ? "Aviso de siniestro" : "Evaluación de riesgo de IA"} <small class="sub">${s.clave}</small></h3>
        <div class="meta">
          ${esAviso ? html`<span>Póliza ${v.numeroPoliza}</span><span>Hecho del ${fechaCorta(v.fechaHecho)}</span>`
                    : html`<span>${v.dominio}</span><span>${v.abogados} abogados</span>`}
          <span>Enviada ${hace(s.inicio)}</span>
        </div>
        <div class="detalle">
          ${esAviso && v.polizaVigente === false && html`<span>La póliza no estaba vigente a la fecha del hecho</span>`}
          ${esAviso && v.resultadoPreverificacion && html`<span><b>Preverificación:</b> ${PREVERIFICACION[v.resultadoPreverificacion] ?? v.resultadoPreverificacion}</span>`}
          ${esAviso && v.numeroExpediente && html`<span><b>Expediente:</b> ${v.numeroExpediente}</span>`}
          ${esAviso && !s.activa && v.fundamentoPreverificacion && html`<span style=${{ flexBasis: "100%" }}>${v.fundamentoPreverificacion}</span>`}
          ${!esAviso && v.puntajeCapa1 !== undefined && html`<span><b>Pre-Score:</b> ${Math.round(v.puntajeCapa1)}</span>`}
          ${!esAviso && v.decision && html`<span><b>Decisión:</b> ${v.decision === "ACCEPT" ? "Aceptada" : "Declinada"}</span>`}
          ${!esAviso && v.primaAnual && html`<span><b>Prima anual:</b> ${usd(v.primaAnual)} · límite ${v.limiteOfrecido}</span>`}
          ${!esAviso && v.fechaReevaluacion && html`<span><b>Plan de mejora:</b> nueva evaluación desde ${fechaCorta(v.fechaReevaluacion)}</span>`}
        </div>
      </div>
      <div class="solicitud-lado">
        ${!esAviso && v.puntaje !== undefined && html`<div style=${{ display: "flex", gap: 10, alignItems: "center" }}>
          <${Tier} t=${v.tier} /><${Medidor} valor=${v.puntaje} tier=${v.tier} /></div>`}
        <${Pildora} tono=${tono}>${s.estado}</${Pildora}>
        ${s.esperaRespuestas && html`<button class="btn btn-primario btn-chico" onClick=${() => responder(s)}>Responder cuestionario</button>`}
      </div>
    </article>`;
  }

  function MisSolicitudes({ firma, responder, lista, error }) {
    if (error) return html`<div class="tarjeta"><${Pildora} tono="error">${error}</${Pildora}></div>`;
    if (!lista) return html`<div class="tarjeta cargando">Cargando solicitudes…</div>`;
    if (!lista.length) return html`<div class="tarjeta"><${Vacio} icono="bandeja" titulo="Aún no hay solicitudes">
      Cuando ${firma} reporte un siniestro o solicite una evaluación, aparecerá aquí con su estado.</${Vacio}></div>`;
    return html`<div class="tarjeta">${lista.map((s) => html`<${Solicitud} key=${s.id} s=${s} responder=${responder} />`)}</div>`;
  }

  // ---------- Formularios
  function useEnvio() {
    const [enviando, setEnviando] = useState(false);
    async function enviar(fn) {
      setEnviando(true);
      try { return await fn(); } catch (e) { avisar(e.message, "error"); return null; } finally { setEnviando(false); }
    }
    return [enviando, enviar];
  }

  function ReportarSiniestro({ cuenta, listo }) {
    const firma = cuenta.nombre;
    const vacio = { numeroPoliza: "", fechaHecho: "", descripcionHecho: "" };
    const [d, setD] = useState(vacio);
    const [enviando, enviar] = useEnvio();
    const [polizas] = useDatos(() => api(`/api/portal/firmas/${cuenta.id}/polizas`), [cuenta.id], 0);
    const set = (k) => (e) => { const v = e.target.value; setD((x) => ({ ...x, [k]: v })); };
    async function enviarAviso(e) {
      e.preventDefault();
      const r = await enviar(() => api("/api/c10/avisos", { method: "POST", body: { firma, ...d } }));
      if (r) { avisar(`Aviso ${r.clave} recibido. Te enviamos el acuse de recibo.`); setD(vacio); listo(); }
    }
    return html`<form class="tarjeta" onSubmit=${enviarAviso}>
      <div class="tarjeta-cab"><div><h2>Reportar un siniestro</h2>
        <p>Avisa a ARCA de una reclamación contra ${firma} por un error atribuido al uso de IA. Proceso C10.</p></div></div>
      <div class="fila-campos">
        <${Campo} etiqueta="Número de póliza" requerido id="numeroPoliza"
          ayuda="ARCA verifica en su registro que la póliza sea de tu firma y que estuviera vigente en la fecha del hecho.">
          <input id="numeroPoliza" list="polizasFirma" value=${d.numeroPoliza} onChange=${set("numeroPoliza")}
            placeholder=${polizas?.[0]?.numero ?? "POL-2026-0042"} required />
          <datalist id="polizasFirma">${(polizas ?? []).map((p) => html`<option key=${p.numero} value=${p.numero} />`)}</datalist></${Campo}>
        <${Campo} etiqueta="Fecha del hecho" requerido id="fechaHecho">
          <input id="fechaHecho" type="date" max=${new Date().toISOString().slice(0, 10)} value=${d.fechaHecho}
            onChange=${set("fechaHecho")} required /></${Campo}>
      </div>
      ${polizas && html`<div class="polizas">
        ${polizas.length ? polizas.map((p) => html`<button key=${p.numero} type="button"
            class=${"poliza" + (d.numeroPoliza === p.numero ? " elegida" : "")}
            onClick=${() => setD((x) => ({ ...x, numeroPoliza: p.numero }))}>
            <b>${p.numero}</b><span>${fechaCorta(p.inicioVigencia)} a ${fechaCorta(p.finVigencia)}</span>
            <span>Límite ${usd(p.limiteCobertura)} · deducible ${usd(p.deducible)}</span></button>`)
          : html`<p class="sub">Tu firma aún no tiene pólizas registradas en ARCA. Si reportas un número que no existe, el aviso se cerrará sin cobertura.</p>`}
      </div>`}
      <${Campo} etiqueta="Descripción del hecho y de la reclamación" requerido id="descripcionHecho">
        <textarea id="descripcionHecho" value=${d.descripcionHecho} onChange=${set("descripcionHecho")} required
          placeholder="Qué ocurrió, qué herramienta de IA intervino y qué reclama el tercero" /></${Campo}>
      <div class="acciones"><button class="btn btn-primario" disabled=${enviando}>
        ${enviando ? "Enviando…" : "Enviar aviso"}</button></div>
    </form>`;
  }

  function EvaluarFirma({ cuenta, listo }) {
    const firma = cuenta.nombre;
    const [d, setD] = useState({ dominio: cuenta.dominio ?? "", abogados: String(cuenta.abogados ?? 5) });
    const [enviando, enviar] = useEnvio();
    const set = (k) => (e) => { const v = e.target.value; setD((x) => ({ ...x, [k]: v })); };
    async function iniciar(e) {
      e.preventDefault();
      const r = await enviar(() => api("/api/c3/evaluaciones", { method: "POST", body: { firma, dominio: d.dominio, abogados: Number(d.abogados) } }));
      if (r) { avisar(`Evaluación ${r.clave} iniciada. Ya puedes responder el cuestionario.`); listo(r); }
    }
    return html`<div class="rejilla rejilla-2">
      <form class="tarjeta" onSubmit=${iniciar}>
        <div class="tarjeta-cab"><div><h2>Solicitar la evaluación de riesgo</h2>
          <p>Proceso C3. ARCA escanea tu huella pública, te presenta un cuestionario y decide si te asegura y a qué precio.</p></div></div>
        <${Campo} etiqueta="Dominio web de la firma" requerido id="dominio" ayuda="Se usa para identificar la firma y buscar a sus abogados.">
          <input id="dominio" value=${d.dominio} onChange=${set("dominio")} placeholder="smithlee.com" required /></${Campo}>
        <${Campo} etiqueta="Número de abogados" requerido id="abogados">
          <input id="abogados" type="number" min="1" value=${d.abogados} onChange=${set("abogados")} required /></${Campo}>
        <div class="acciones"><button class="btn btn-primario" disabled=${enviando}>${enviando ? "Calculando Pre-Score…" : "Iniciar evaluación"}</button></div>
      </form>
      <div class="tarjeta">
        <div class="tarjeta-cab"><div><h2>Cómo se decide</h2><p>El puntaje compuesto combina 30 % de Pre-Score y 70 % del cuestionario.</p></div></div>
        <table><tbody>
          <tr><td><${Tier} t="FORTRESS" /></td><td>80 o más</td><td>Aceptación automática</td></tr>
          <tr><td><${Tier} t="FORTIFIED" /></td><td>65 a 79</td><td>Aceptación automática</td></tr>
          <tr><td><${Tier} t="GUARDED" /></td><td>45 a 64</td><td>Revisión del suscriptor (48 h)</td></tr>
          <tr><td><${Tier} t="EXPOSED" /></td><td>25 a 44</td><td>Actuario y director (5 días)</td></tr>
          <tr><td><${Tier} t="CRITICAL" /></td><td>menos de 25</td><td>Declinada con plan de mejora</td></tr>
        </tbody></table>
        <p class="sub" style=${{ marginTop: 12 }}>Un reclamo pagado, un incidente de IA o no tener E&O vigente llevan el caso a revisión humana.</p>
      </div>
    </div>`;
  }

  // ---------- Cuestionario
  function Cuestionario({ s, preguntas, cerrar, listo }) {
    const [resp, setResp] = useState(() => preguntas.map(() => null));
    const [ant, setAnt] = useState({ reclamoPagado: false, incidenteIA: false, tieneEOVigente: true, declaracionVeracidad: false });
    const [enviando, setEnviando] = useState(false);
    const respondidas = resp.filter((r) => r !== null).length;
    const completo = respondidas === preguntas.length && ant.declaracionVeracidad;
    const marcar = (i, v) => setResp((actual) => actual.map((r, j) => (j === i ? v : r)));
    async function enviar() {
      setEnviando(true);
      try {
        const r = await api(`/api/c3/evaluaciones/${s.id}/respuestas`, { method: "POST", body: { respuestas: resp, ...ant } });
        avisar(`Respuestas enviadas. Puntaje del cuestionario: ${r.puntajeCapa2}.`);
        listo();
      } catch (e) { avisar(e.message, "error"); } finally { setEnviando(false); }
    }
    const casilla = (k, texto) => html`<label class="casilla"><input type="checkbox" checked=${ant[k]}
      onChange=${(e) => { const v = e.target.checked; setAnt((a) => ({ ...a, [k]: v })); }} /> ${texto}</label>`;
    return html`<${Modal} titulo="Cuestionario de gobernanza de IA" sub=${`${s.clave} · ${s.variables.firma} · ${s.variables.dominio}`} alCerrar=${cerrar}>
      <div style=${{ display: "flex", justifyContent: "space-between", fontSize: ".85rem", margin: "12px 0 6px" }}>
        <span class="sub">${respondidas} de ${preguntas.length} respondidas</span></div>
      <div class="progreso"><div style=${{ width: `${(100 * respondidas) / preguntas.length}%` }} /></div>
      <div style=${{ marginTop: 6 }}>
        ${preguntas.map((p, i) => html`<div key=${i} class=${"pregunta" + (resp[i] !== null ? " respondida" : "")}>
          <span class="pregunta-num">${i + 1}</span><span>${p}</span>
          <div class="sino" role="group" aria-label=${`Pregunta ${i + 1}`}>
            <button type="button" class=${resp[i] === true ? "si" : ""} aria-pressed=${resp[i] === true} onClick=${() => marcar(i, true)}>Sí</button>
            <button type="button" class=${resp[i] === false ? "no" : ""} aria-pressed=${resp[i] === false} onClick=${() => marcar(i, false)}>No</button>
          </div></div>`)}
      </div>
      <h3 style=${{ margin: "20px 0 10px", fontSize: "1rem" }}>Antecedentes</h3>
      ${casilla("reclamoPagado", "La firma ha pagado un reclamo por mala praxis en los últimos 5 años")}
      ${casilla("incidenteIA", "La firma ha tenido un incidente relacionado con el uso de IA")}
      ${casilla("tieneEOVigente", "La firma tiene un seguro de errores y omisiones (E&O) vigente")}
      <h3 style=${{ margin: "20px 0 10px", fontSize: "1rem" }}>Declaración</h3>
      ${casilla("declaracionVeracidad", "Declaro que las respuestas son veraces y completas")}
      <div class="acciones">
        <button class="btn btn-primario" disabled=${!completo || enviando} onClick=${enviar}>${enviando ? "Enviando…" : "Enviar respuestas"}</button>
        <button class="btn btn-secundario" onClick=${cerrar}>Cancelar</button>
      </div>
    </${Modal}>`;
  }

  // ---------- Registro e ingreso de la firma (los datos quedan en arca-servicios)
  function Acceso({ alEntrar }) {
    const [modo, setModo] = useState("ingresar");
    const [d, setD] = useState({ nombre: "", dominio: "", email: "", abogados: "5" });
    const [enviando, enviar] = useEnvio();
    const set = (k) => (e) => { const v = e.target.value; setD((x) => ({ ...x, [k]: v })); };
    async function enviarAcceso(e) {
      e.preventDefault();
      const r = await enviar(() => modo === "ingresar"
        ? api(`/api/portal/firmas?email=${encodeURIComponent(d.email)}`)
        : api("/api/portal/firmas", { method: "POST", body: { ...d, abogados: Number(d.abogados) } }));
      if (r) { avisar(modo === "ingresar" ? `Ingresaste como ${r.nombre}` : `${r.nombre} quedó registrada en ARCA`); alEntrar(r); }
    }
    return html`<div class="rejilla rejilla-2">
      <form class="tarjeta" onSubmit=${enviarAcceso}>
        <div class="pestanas" role="tablist" style=${{ boxShadow: "none", marginBottom: 16 }}>
          ${[["ingresar", "Ingresar"], ["registrar", "Registrar mi firma"]].map(([k, t]) => html`<button key=${k} type="button" role="tab"
            aria-selected=${modo === k} class=${"pestana" + (modo === k ? " activa" : "")} onClick=${() => setModo(k)}>${t}</button>`)}
        </div>
        ${modo === "registrar" && html`
          <${Campo} etiqueta="Nombre de la firma" requerido id="regNombre">
            <input id="regNombre" value=${d.nombre} onChange=${set("nombre")} placeholder="Garcia Legal Group" required /></${Campo}>
          <div class="fila-campos">
            <${Campo} etiqueta="Dominio web" requerido id="regDominio">
              <input id="regDominio" value=${d.dominio} onChange=${set("dominio")} placeholder="garcialegal.com" required /></${Campo}>
            <${Campo} etiqueta="Número de abogados" requerido id="regAbogados">
              <input id="regAbogados" type="number" min="1" value=${d.abogados} onChange=${set("abogados")} required /></${Campo}>
          </div>`}
        <${Campo} etiqueta="Email de contacto" requerido id="regEmail"
          ayuda=${modo === "ingresar" ? "Firmas de ejemplo: riesgos@smithlee.com, socios@garcialegal.com, operaciones@riveracole.com" : null}>
          <input id="regEmail" type="email" value=${d.email} onChange=${set("email")} placeholder="riesgos@smithlee.com" required /></${Campo}>
        <div class="acciones"><button class="btn btn-primario" disabled=${enviando}>
          ${modo === "ingresar" ? "Ingresar" : "Registrar firma"}</button></div>
      </form>
      <div class="tarjeta"><${Vacio} icono="edificio" titulo="Portal de la firma">
        Registra tu firma una sola vez. Con ella puedes reportar siniestros sobre tus pólizas, pedir tu evaluación de riesgo de IA
        y seguir el estado de cada solicitud.</${Vacio}></div>
    </div>`;
  }

  // ---------- Página
  function Portal() {
    const [cuenta, setCuenta] = useState(() => {
      try { return JSON.parse(guardado("arca.cuenta", "null")); } catch { return null; }
    });
    const firma = cuenta?.nombre ?? "";
    const [pestana, setPestana] = useState("solicitudes");
    const [abierta, setAbierta] = useState(null);
    const [preguntas] = useDatos(() => api("/api/c3/cuestionario"), [], 0);
    const [lista, error, recargar] = useDatos(
      () => (firma ? api(`/api/portal/solicitudes?firma=${encodeURIComponent(firma)}`) : Promise.resolve([])), [firma]);
    const pendientes = (lista ?? []).filter((s) => s.esperaRespuestas).length;

    function entrar(c) {
      guardar("arca.cuenta", JSON.stringify(c));
      setCuenta(c);
      setPestana("solicitudes");
    }
    function salir() {
      guardar("arca.cuenta", "null");
      setCuenta(null);
    }
    /** Tras iniciar la evaluación, abre de una vez el cuestionario que quedó esperando respuestas. */
    async function trasEvaluar(r) {
      setPestana("solicitudes");
      recargar();
      try {
        const s = (await api(`/api/portal/solicitudes?firma=${encodeURIComponent(firma)}`)).find((x) => x.id === r.id);
        if (s?.esperaRespuestas) setAbierta(s);
      } catch (e) { avisar(e.message, "error"); }
    }

    const PESTANAS = [["solicitudes", "Mis solicitudes"], ["siniestro", "Reportar siniestro"], ["evaluacion", "Evaluar mi firma"]];
    return html`
      <${Topbar} activo="portal.html" />
      <section class="hero"><div class="hero-in">
        <div><h1>${firma ? `Hola, ${firma}` : "Portal de la firma"}</h1>
          <p>Reporta siniestros, evalúa el riesgo de IA de tu firma y sigue cada solicitud en un solo lugar.</p></div>
        ${cuenta && html`<div class="firma-box">
          <div class="avatar">${iniciales(firma)}</div>
          <div style=${{ flex: 1, minWidth: 0 }}><b>${firma}</b><div style=${{ opacity: 0.8, fontSize: ".85rem" }}>${cuenta.email} · ${cuenta.dominio}</div></div>
          <button class="btn btn-secundario" style=${{ color: "#fff", borderColor: "rgba(255,255,255,.4)" }} onClick=${salir}>Salir</button>
        </div>`}
      </div></section>
      <main class="contenedor">
        ${!cuenta ? html`<${Acceso} alEntrar=${entrar} />`
        : html`
          <div class="pestanas" role="tablist">
            ${PESTANAS.map(([k, t]) => html`<button key=${k} role="tab" aria-selected=${pestana === k}
              class=${"pestana" + (pestana === k ? " activa" : "")} onClick=${() => setPestana(k)}>
              ${t}${k === "solicitudes" && pendientes > 0 && html`<span class="contador">${pendientes}</span>`}</button>`)}
          </div>
          ${pestana === "solicitudes" && html`<${MisSolicitudes} firma=${firma} lista=${lista} error=${error} responder=${setAbierta} />`}
          ${pestana === "siniestro" && html`<${ReportarSiniestro} cuenta=${cuenta} listo=${() => { recargar(); setPestana("solicitudes"); }} />`}
          ${pestana === "evaluacion" && html`<${EvaluarFirma} cuenta=${cuenta} listo=${trasEvaluar} />`}`}
      </main>
      ${abierta && preguntas && html`<${Cuestionario} s=${abierta} preguntas=${preguntas} cerrar=${() => setAbierta(null)}
        listo=${() => { setAbierta(null); recargar(); }} />`}
      <${Avisos} />`;
  }

  ARCA.montar(Portal);
})();

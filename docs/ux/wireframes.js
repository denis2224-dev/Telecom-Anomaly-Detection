let page = "dashboard";
const state = document.querySelector("#state"),
  content = document.querySelector("#content");
const notices = {
  disconnected:
    "Connection interrupted. Showing the last confirmed fixture snapshot from 15:43 EEST. Reconnect would first verify your session and refresh incidents.",
  conflict:
    "This incident changed. Review the latest version before trying again. Your unsaved note would be preserved.",
  forbidden:
    "You do not have permission to perform this action. Contact a supervisor if you need access.",
};
function rows() {
  return `<div class="rows"><div class="row"><span>INC-1042</span><div><a href="#incident" data-detail><strong>Sustained packet loss</strong></a><small>Chișinău · Edge 04 · Unassigned</small></div><span class="status">HIGH / OPEN</span><span>15:42 EEST</span></div><div class="row"><span>INC-1041</span><div><a href="#incident-1041" data-secondary><strong>Traffic above baseline</strong></a><small>Bălți · Core 02 · Demo analyst</small></div><span class="status">INVESTIGATING</span><span>15:38 EEST</span></div></div>`;
}
function render() {
  let s = state.value;
  document.querySelector("#actor").hidden = [
    "signed-out",
    "session-expired",
    "loading",
  ].includes(s);
  document
    .querySelector("#dashboardNav")
    .classList.toggle("active", page === "dashboard");
  document
    .querySelector("#incidentsNav")
    .classList.toggle("active", page !== "dashboard");
  let notice = notices[s]
    ? `<div class="notice" role="status">${notices[s]}</div>`
    : "";
  if (s === "signed-out" || s === "session-expired") {
    content.innerHTML = `<section class="signed"><p class="eyebrow">ANALYST WORKSPACE</p><h1>${s === "session-expired" ? "Your session has expired." : "A clearer view of your network."}</h1><p class="muted">${s === "session-expired" ? "Sign in again to continue reviewing incidents." : "Sign in to review incidents, understand the evidence, and coordinate the response."}</p><button class="primary" id="login">Continue to sign in</button><p id="loginNote" class="muted">Your organization manages sign-in.</p></section>`;
    document.querySelector("#login").onclick = () => {
      document.querySelector("#loginNote").textContent =
        "Prototype only: the production button navigates to /oauth2/authorization/keycloak through the backend. No real login is connected.";
    };
    return;
  }
  let heading =
    page === "dashboard"
      ? "Network overview"
      : page === "incidents"
        ? "Incident queue"
        : "Incident detail";
  content.innerHTML = `<h1>${heading}</h1><p class="muted subtitle">${page === "dashboard" ? "Know what needs attention. Start with the evidence." : "Review the affected entity, evidence, and next action."}</p>${notice}`;
  if (["loading", "empty", "error", "forbidden"].includes(s)) {
    const copy = {
      loading: [
        "Loading your workspace",
        "Checking your session and retrieving incidents.",
      ],
      empty: [
        "No incidents match this view",
        "There are no incidents to review in the selected view.",
      ],
      error: [
        "Incidents could not be loaded",
        "The service is unavailable. Your filters are preserved. Try loading this view again.",
      ],
      forbidden: [
        "Action unavailable",
        "Your current role does not permit this action.",
      ],
    }[s];
    content.innerHTML += `<section class="state" ${s === "loading" ? 'aria-busy="true"' : ""}><h2>${copy[0]}</h2><p class="muted">${copy[1]}</p>${s === "loading" ? '<div class="skeleton" aria-hidden="true"></div><div class="skeleton short" aria-hidden="true"></div>' : `<button id="retry">${s === "empty" ? "Clear filters" : s === "forbidden" ? "Return to overview" : "Retry loading"}</button><p class="muted" id="retryNote"></p>`}</section>`;
    const retry = document.querySelector("#retry");
    if (retry)
      retry.onclick = () => {
        if (s === "forbidden") {
          state.value = "live";
          page = "dashboard";
          render();
        } else
          document.querySelector("#retryNote").textContent =
            s === "empty"
              ? "Filters cleared. This empty fixture still contains no incidents."
              : "Retry requested. This error fixture remains unavailable; choose Live (fixture) to review a successful response.";
      };
    return;
  }
  if (page === "detail" || page === "secondary") {
    const second = page === "secondary";
    content.innerHTML += `<button class="back" id="back">← Incident queue</button><section class="detail"><span class="eyebrow">${second ? "INC-1041 / INVESTIGATING" : "INC-1042 / HIGH / OPEN"}</span><h2>${second ? "Traffic above the recent baseline" : "Sustained packet loss on the eastern uplink"}</h2><p class="muted">${second ? "Bălți · Core 02 · Assigned to Demo analyst" : "Chișinău · Edge 04 · Unassigned · 11 Sep 2026, 15:42 EEST"}</p>${second ? "<p>Assigned analyst is reviewing the traffic window. Detailed evidence has not been supplied for this design fixture.</p>" : `<div class="evidence"><section><h3>Evidence</h3><dl><dt>Observed packet loss</dt><dd>8.4%</dd><dt>Configured threshold</dt><dd>2.0%</dd><dt>Baseline</dt><dd>Unavailable</dd></dl></section><section><h3>Detection context</h3><p class="muted">Rule-only detection. The ML model was unavailable for this incident.</p><dl><dt>Risk score / uncapped score</dt><dd>82 / 82 · illustrative</dd><dt>Rule contribution</dt><dd>82 · Packet loss above threshold</dd></dl></section></div><h3>Activity</h3><p class="muted">15:42 EEST · Rule engine created the incident after the threshold was exceeded.</p>`}<button disabled>Claim incident</button><p class="muted">Workflow actions are specified for later implementation; this preview does not change incidents.</p></section>`;
    document.querySelector("#back").onclick = () => {
      page = "incidents";
      render();
    };
    return;
  }
  if (page === "dashboard")
    content.innerHTML += `<div class="overview"><section class="priority"><span class="eyebrow">PRIORITY INCIDENT / INC-1042</span><div><span class="tag">HIGH SEVERITY</span><span class="tag">OPEN · UNASSIGNED</span></div><h2>Sustained packet loss on the eastern uplink</h2><p class="muted">Chișinău · Edge 04</p><p>Packet loss exceeded the configured threshold. Review the link evidence and confirm the affected service before starting an investigation.</p><div class="metadata">Detected 11 Sep, 15:42 EEST · Rule-based detection</div><button class="primary" id="reviewIncident">Review incident →</button></section><aside class="support" aria-label="Supporting context"><section><h3>Start with unassigned incidents</h3><p>The priority incident has no owner. Review its impact before claiming the investigation.</p></section><section><h3>Check the observation window</h3><p>Last fixture update: 15:43 EEST. Traffic telemetry is unavailable in this preview.</p></section><section><h3>Understand the detection</h3><p>ML was unavailable for this incident. Rule evidence is available; no model confidence is implied.</p></section></aside></div>`;
  content.innerHTML += `<div class="section-head"><h2>${page === "dashboard" ? "Recent incidents" : "Current incidents"}</h2>${page === "dashboard" ? '<button id="viewQueue">View queue →</button>' : '<span class="muted">Design sample · no server pagination</span>'}</div>${rows()}`;
  document.querySelectorAll("[data-detail],#reviewIncident").forEach(
    (el) =>
      (el.onclick = (e) => {
        e.preventDefault();
        page = "detail";
        render();
      }),
  );
  document.querySelectorAll("[data-secondary]").forEach(
    (el) =>
      (el.onclick = (e) => {
        e.preventDefault();
        page = "secondary";
        render();
      }),
  );
  let q = document.querySelector("#viewQueue");
  if (q)
    q.onclick = () => {
      page = "incidents";
      render();
    };
}
state.onchange = render;
document.querySelector("#dashboardNav").onclick = () => {
  page = "dashboard";
  render();
};
document.querySelector("#incidentsNav").onclick = () => {
  page = "incidents";
  render();
};
render();

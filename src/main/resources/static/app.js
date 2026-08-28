const state = {
  user: null,
  clusters: [],
  servers: [],
  databases: [],
  tables: [],
  tableFilter: "",
  selectedClusterId: null,
  selectedDatabase: null,
  selectedTable: null,
  selectedDetail: null,
  clusterStatuses: {},
  activePage: "cluster",
};

const $ = (id) => document.getElementById(id);

const DEFAULT_DATABASE = "fluss";

const appBaseUrl = new URL(".", document.currentScript?.src || window.location.href);

function appUrl(path) {
  const value = String(path);
  if (/^[a-z][a-z0-9+.-]*:/i.test(value)) {
    return value;
  }
  return new URL(value.replace(/^\/+/, ""), appBaseUrl).toString();
}

async function api(path, options = {}) {
  const response = await fetch(appUrl(path), {
    credentials: "same-origin",
    headers: { "Content-Type": "application/json", ...(options.headers || {}) },
    ...options,
  });
  if (response.status === 401) {
    showLogin();
    const error = new Error("Authentication required");
    error.authRequired = true;
    throw error;
  }
  const text = await response.text();
  const payload = text ? JSON.parse(text) : null;
  if (!response.ok) {
    throw new Error(payload?.message || payload?.error || `Request failed: ${response.status}`);
  }
  return payload;
}

function clusterBase() {
  if (!state.selectedClusterId) {
    throw new Error("Select a cluster first.");
  }
  return `/api/clusters/${encodeURIComponent(state.selectedClusterId)}`;
}

function showLogin(message = "") {
  $("login-page").hidden = false;
  $("app-shell").hidden = true;
  $("login-error").textContent = message;
}

function showApp() {
  $("login-page").hidden = true;
  $("app-shell").hidden = false;
}

function setStatus(text, isError = false) {
  $("cluster-status").textContent = text;
  $("cluster-status").className = isError ? "error" : "";
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

async function initialize() {
  try {
    const me = await api("/api/auth/me");
    state.user = me.username;
    $("avatar").textContent = (me.username || "FM").slice(0, 2).toUpperCase();
    showApp();
    await loadClusters().catch(showError);
  } catch (error) {
    if (error.authRequired) {
      showLogin();
      return;
    }
    showLogin();
  }
}

async function login(event) {
  event.preventDefault();
  try {
    const result = await api("/api/auth/login", {
      method: "POST",
      body: JSON.stringify({
        username: $("login-username").value.trim(),
        password: $("login-password").value,
      }),
    });
    state.user = result.username;
    $("avatar").textContent = (result.username || "FM").slice(0, 2).toUpperCase();
    showApp();
  } catch (error) {
    showLogin(error.message);
    return;
  }
  await loadClusters().catch(showError);
}

async function logout() {
  await api("/api/auth/logout", { method: "POST" }).catch(() => null);
  state.user = null;
  showLogin();
}

async function loadClusters() {
  state.clusters = await api("/api/clusters");
  if (!state.clusters.some((cluster) => cluster.id === state.selectedClusterId)) {
    state.selectedClusterId = state.clusters[0]?.id || null;
  }
  renderClusters();
  if (state.selectedClusterId) {
    await loadClusterMetadata();
  } else {
    renderNoCluster();
  }
}

async function loadClusterMetadata() {
  setStatus("Connecting");
  const base = clusterBase();
  const clusterId = state.selectedClusterId;
  updateClusterStatus(clusterId, "connecting", "Connecting");
  let health;
  let servers;
  let databases;
  try {
    [health, servers, databases] = await Promise.all([
      api(`${base}/health`),
      api(`${base}/servers`).catch((error) => {
        if (error.authRequired) {
          throw error;
        }
        return [];
      }),
      api(`${base}/databases`),
    ]);
  } catch (error) {
    if (error.authRequired) {
      throw error;
    }
    handleClusterConnectionError(clusterId, error);
    return;
  }
  state.servers = servers;
  state.databases = databases;
  const serverCounts = serverTypeCounts(servers);
  setStatus("Connected");
  updateClusterStatus(clusterId, "connected", "Connected");
  $("cluster-sidebar-summary").textContent =
    `${servers.length} servers · ${serverCounts.TABLET_SERVER || 0} tablet · ${serverCounts.COORDINATOR || 0} coord`;
  $("summary").innerHTML = `
    <span class="pill">${escapeHtml(health.bootstrapServers)}</span>
    <span class="pill">${servers.length} servers</span>
    <span class="pill">${serverCounts.TABLET_SERVER || 0} tablet servers</span>
    <span class="pill">${serverCounts.COORDINATOR || 0} coordinators</span>
  `;
  $("database-summary").innerHTML = `
    <span class="pill">${escapeHtml(health.bootstrapServers)}</span>
    <span class="pill">${databases.length} databases</span>
  `;
  renderClusterScale(servers);
  renderDatabases();
  $("new-database").disabled = false;
  if (state.selectedDatabase && databases.includes(state.selectedDatabase)) {
    await loadTables(state.selectedDatabase).catch(showTableError);
  } else {
    renderNoDatabase();
  }
}

function updateClusterStatus(clusterId, status, message) {
  if (!clusterId) {
    return;
  }
  state.clusterStatuses[clusterId] = { status, message };
  renderClusters();
}

function handleClusterConnectionError(clusterId, error) {
  const friendly = friendlyError(error, { clusterId });
  state.servers = [];
  state.databases = [];
  updateClusterStatus(clusterId, "error", friendly.detail);
  setStatus("Disconnected", true);
  $("cluster-sidebar-summary").textContent = "Cluster unavailable";
  $("summary").innerHTML = `<span class="pill danger" title="${escapeHtml(friendly.detail)}">${escapeHtml(friendly.summary)}</span>`;
  $("database-summary").innerHTML = `<span class="pill danger">Cluster unavailable</span>`;
  $("cluster-scale").className = "cluster-scale empty";
  $("cluster-scale").innerHTML = errorBlock(friendly);
  $("database-list").innerHTML = "";
  $("new-database").disabled = true;
  renderNoDatabase();
}

function renderNoCluster() {
  setStatus("Disconnected", true);
  $("cluster-sidebar-summary").textContent = "No cluster selected";
  $("summary").innerHTML = "";
  $("database-summary").innerHTML = "";
  $("cluster-scale").className = "cluster-scale empty";
  $("cluster-scale").textContent = "Create a cluster to begin.";
  $("edit-cluster").disabled = true;
  $("delete-cluster").disabled = true;
  $("cluster-list").innerHTML = "";
  $("database-list").innerHTML = "";
  $("new-database").disabled = true;
  renderNoDatabase();
}

function renderClusters() {
  $("cluster-list").innerHTML = state.clusters.length
    ? state.clusters.map((cluster) => `
        <button class="nav-item ${cluster.id === state.selectedClusterId ? "active" : ""}" data-cluster="${escapeHtml(cluster.id)}">
          <span class="nav-item-heading">
            <strong>${escapeHtml(cluster.name || cluster.id)}</strong>
            ${clusterStatusBadge(cluster)}
          </span>
          <small>${escapeHtml(cluster.bootstrapServers)}</small>
        </button>
      `).join("")
    : `<div class="empty-state">No clusters</div>`;
  document.querySelectorAll("[data-cluster]").forEach((button) => {
    button.addEventListener("click", async () => {
      state.selectedClusterId = button.dataset.cluster;
      state.selectedDatabase = null;
      state.selectedTable = null;
      renderClusters();
      showClusterPage();
      await loadClusterMetadata().catch(showError);
    });
  });
  $("edit-cluster").disabled = !state.selectedClusterId;
  $("delete-cluster").disabled = !state.selectedClusterId;
}

function clusterStatusBadge(cluster) {
  const current = state.clusterStatuses[cluster.id] || { status: "unknown", message: "Not checked" };
  const labels = {
    connected: "Online",
    connecting: "Checking",
    error: "Offline",
    unknown: "Unknown",
  };
  return `<span class="cluster-status-badge ${escapeHtml(current.status)}" title="${escapeHtml(current.message)}">${labels[current.status] || "Unknown"}</span>`;
}

function renderDatabases() {
  $("database-list").innerHTML = state.databases.length
    ? state.databases.map((database) => `
        <button class="nav-item ${state.activePage === "explorer" && database === state.selectedDatabase ? "active" : ""}" data-db="${escapeHtml(database)}">
          ${escapeHtml(database)}
        </button>
      `).join("")
    : `<div class="empty-state">No databases</div>`;
  document.querySelectorAll("[data-db]").forEach((button) => {
    button.addEventListener("click", () => selectDatabase(button.dataset.db).catch(showTableError));
  });
}

async function selectDatabase(database) {
  showExplorerPage();
  state.selectedDatabase = database;
  state.selectedTable = null;
  state.tableFilter = "";
  $("table-search").value = "";
  renderDatabases();
  await loadTables(database);
}

async function loadTables(database) {
  $("page-title").textContent = database;
  $("detail-title").textContent = "Table Detail";
  $("detail-badge").textContent = "Idle";
  ["show-ddl", "show-preview", "show-query", "edit-table", "delete-table"].forEach((id) => ($(id).hidden = true));
  state.selectedDetail = null;
  $("detail").className = "detail empty";
  $("detail").textContent = "Choose a table to inspect schema, keys, partitions, and properties.";
  $("table-count").textContent = "Loading";
  $("table-search").disabled = true;
  $("new-table").disabled = false;
  const defaultDatabase = database === DEFAULT_DATABASE;
  $("delete-database").disabled = defaultDatabase;
  $("delete-database").title = defaultDatabase
    ? "The default database is required for cluster operation and cannot be deleted."
    : "";
  $("table-list").className = "table-list empty";
  $("table-list").textContent = "Loading tables";
  state.tables = await api(`${clusterBase()}/databases/${encodeURIComponent(database)}/tables`);
  $("table-search").disabled = state.tables.length === 0;
  renderTables();
}

function renderNoDatabase() {
  state.tables = [];
  state.selectedDatabase = null;
  state.selectedTable = null;
  state.selectedDetail = null;
  $("page-title").textContent = "Metadata Explorer";
  $("detail-title").textContent = "Table Detail";
  $("detail-badge").textContent = "Idle";
  ["show-ddl", "show-preview", "show-query", "edit-table", "delete-table"].forEach((id) => ($(id).hidden = true));
  $("detail").className = "detail empty";
  $("detail").textContent = "Choose a database and table.";
  $("table-count").textContent = "0";
  $("table-search").disabled = true;
  $("new-table").disabled = true;
  $("delete-database").disabled = true;
  $("delete-database").title = "";
  $("table-list").className = "table-list empty";
  $("table-list").textContent = "Select a database";
}

function renderTables() {
  const filter = state.tableFilter.trim().toLowerCase();
  const filteredTables = filter
    ? state.tables.filter((table) => table.toLowerCase().includes(filter))
    : state.tables;
  $("table-count").textContent = filter ? `${filteredTables.length}/${state.tables.length}` : state.tables.length;
  $("table-list").className = filteredTables.length ? "table-list" : "table-list empty";
  $("table-list").innerHTML = filteredTables.length
    ? filteredTables.map((table) => `
        <button class="table-item ${table === state.selectedTable ? "active" : ""}" data-table="${escapeHtml(table)}">
          ${highlightMatch(table, state.tableFilter)}
        </button>
      `).join("")
    : state.tables.length ? "No matching tables" : "No tables found";
  document.querySelectorAll("[data-table]").forEach((button) => {
    button.addEventListener("click", () => selectTable(button.dataset.table).catch(showDetailError));
  });
}

function highlightMatch(value, filter) {
  if (!filter) {
    return escapeHtml(value);
  }
  const index = value.toLowerCase().indexOf(filter.toLowerCase());
  if (index < 0) {
    return escapeHtml(value);
  }
  return `${escapeHtml(value.slice(0, index))}<mark>${escapeHtml(value.slice(index, index + filter.length))}</mark>${escapeHtml(value.slice(index + filter.length))}`;
}

async function selectTable(table) {
  state.selectedTable = table;
  state.selectedDetail = null;
  document.querySelectorAll("[data-table]").forEach((button) => {
    button.classList.toggle("active", button.dataset.table === table);
  });
  $("detail-title").textContent = table;
  $("detail-badge").textContent = "Loading";
  ["show-ddl", "show-preview", "show-query", "edit-table"].forEach((id) => ($(id).hidden = true));
  $("delete-table").hidden = false;
  const detail = await api(`${clusterBase()}/databases/${encodeURIComponent(state.selectedDatabase)}/tables/${encodeURIComponent(table)}`);
  state.selectedDetail = detail;
  $("detail-badge").textContent = detail.stats?.available ? `${detail.stats.rowCount} rows` : "Metadata";
  $("show-ddl").hidden = !detail.ddl;
  $("show-preview").hidden = false;
  $("show-query").hidden = !hasPrimaryKeys(detail);
  $("edit-table").hidden = false;
  renderQueryColumns(detail);
  renderDetail(detail);
}

function renderDetail(detail) {
  $("detail").className = "detail";
  $("detail").innerHTML = `
    <div class="metric-row">
      <div class="metric"><strong>${escapeHtml(detail.numBuckets)}</strong><span>Buckets</span></div>
      <div class="metric"><strong>${escapeHtml(detail.columns.length)}</strong><span>Columns</span></div>
      <div class="metric"><strong>${detail.partitioned ? "Yes" : "No"}</strong><span>Partitioned</span></div>
      <div class="metric"><strong>${detail.stats?.available ? escapeHtml(detail.stats.rowCount) : "N/A"}</strong><span>Rows</span></div>
    </div>
    <div class="kv">
      <div>Table ID</div><div><code>${escapeHtml(detail.tableId)}</code></div>
      <div>Schema ID</div><div><code>${escapeHtml(detail.schemaId)}</code></div>
      <div>Primary Keys</div><div>${chips(detail.primaryKeys)}</div>
      <div>Bucket Keys</div><div>${chips(detail.bucketKeys)}</div>
      <div>Partition Keys</div><div>${chips(detail.partitionKeys)}</div>
      <div>Created</div><div>${escapeHtml(detail.createdTime || "N/A")}</div>
      <div>Modified</div><div>${escapeHtml(detail.modifiedTime || "N/A")}</div>
      <div>Comment</div><div>${escapeHtml(detail.comment || "")}</div>
    </div>
    <h3>Columns</h3>
    ${columnsTable(detail.columns)}
    <h3>Partitions</h3>
    ${partitionsTable(detail.partitions)}
    <h3>Properties</h3>
    ${propertiesTable(detail.properties)}
  `;
}

function chips(values) {
  return values?.length ? values.map((value) => `<code>${escapeHtml(value)}</code>`).join(" ") : "None";
}

function columnsTable(columns) {
  return `<table><thead><tr><th>ID</th><th>Name</th><th>Type</th><th>Aggregation</th><th>Comment</th></tr></thead><tbody>
    ${columns.map((column) => `<tr><td>${escapeHtml(column.id)}</td><td>${escapeHtml(column.name)}</td><td><code>${escapeHtml(column.type)}</code></td><td>${escapeHtml(column.aggFunction || "")}</td><td>${escapeHtml(column.comment || "")}</td></tr>`).join("")}
  </tbody></table>`;
}

function partitionsTable(partitions) {
  if (!partitions?.length) {
    return `<p class="empty">No partitions.</p>`;
  }
  return `<table><thead><tr><th>ID</th><th>Name</th><th>Spec</th></tr></thead><tbody>
    ${partitions.map((partition) => `<tr><td>${escapeHtml(partition.id)}</td><td>${escapeHtml(partition.name)}</td><td><code>${escapeHtml(partition.spec)}</code></td></tr>`).join("")}
  </tbody></table>`;
}

function propertiesTable(properties) {
  const entries = Object.entries(properties || {})
    .filter(([key]) => key !== "table.datalake.iceberg.warehouse");
  if (!entries.length) {
    return `<p class="empty">No properties.</p>`;
  }
  return `<table><thead><tr><th>Key</th><th>Value</th></tr></thead><tbody>
    ${entries.map(([key, value]) => `<tr><td>${escapeHtml(key)}</td><td><code>${escapeHtml(value)}</code></td></tr>`).join("")}
  </tbody></table>`;
}

function renderClusterScale(servers) {
  const target = $("cluster-scale");
  if (!servers?.length) {
    target.className = "cluster-scale empty";
    target.textContent = "Cluster scale unavailable.";
    return;
  }
  const counts = serverTypeCounts(servers);
  const types = orderedServerTypes(counts);
  target.className = "cluster-scale";
  target.innerHTML = `
    <div class="cluster-metrics">
      <div class="cluster-metric"><strong>${servers.length}</strong><span>Total servers</span></div>
      ${types.map((type) => `<div class="cluster-metric"><strong>${counts[type]}</strong><span>${escapeHtml(serverTypeLabel(type))}</span></div>`).join("")}
    </div>
    <div class="server-list">
      ${servers.slice().sort((a, b) => String(a.type).localeCompare(String(b.type)) || Number(a.id) - Number(b.id)).map((server) => `
        <div class="server-node">
          <span class="server-type">${escapeHtml(serverTypeLabel(server.type))}</span>
          <code>${escapeHtml(server.host)}:${escapeHtml(server.port)}</code>
          <span>#${escapeHtml(server.id)}</span>
          ${server.rack ? `<span>${escapeHtml(server.rack)}</span>` : ""}
        </div>`).join("")}
    </div>`;
}

function serverTypeCounts(servers) {
  return (servers || []).reduce((counts, server) => {
    const type = server.type || "UNKNOWN";
    counts[type] = (counts[type] || 0) + 1;
    return counts;
  }, {});
}

function orderedServerTypes(counts) {
  const preferred = ["TABLET_SERVER", "COORDINATOR", "UNKNOWN"];
  return [...preferred.filter((type) => counts[type] !== undefined), ...Object.keys(counts).filter((type) => !preferred.includes(type)).sort()];
}

function serverTypeLabel(type) {
  if (type === "TABLET_SERVER") return "Tablet Servers";
  if (type === "COORDINATOR") return "Coordinators";
  return String(type || "UNKNOWN").toLowerCase().replaceAll("_", " ").replace(/\b\w/g, (letter) => letter.toUpperCase());
}

function showClusterPage() {
  state.activePage = "cluster";
  $("cluster-page").hidden = false;
  $("explorer-page").hidden = true;
  updateActiveNavigation();
  hideDdl(); hidePreview(); hideQuery();
}

function showExplorerPage() {
  state.activePage = "explorer";
  $("cluster-page").hidden = true;
  $("explorer-page").hidden = false;
  updateActiveNavigation();
}

function updateActiveNavigation() {
  $("cluster-nav").classList.toggle("active", state.activePage === "cluster");
  document.querySelectorAll("[data-db]").forEach((button) => {
    button.classList.toggle("active", state.activePage === "explorer" && button.dataset.db === state.selectedDatabase);
  });
}

function showDdl() {
  $("ddl-title").textContent = `${state.selectedDetail.database}.${state.selectedDetail.name} DDL`;
  $("ddl-code").textContent = state.selectedDetail.ddl;
  $("ddl-modal").hidden = false;
}

function hideDdl() {
  $("ddl-modal").hidden = true;
}

async function showPreview() {
  $("preview-title").textContent = `${state.selectedDatabase}.${state.selectedTable} Preview`;
  $("preview-meta").textContent = "Loading latest 100 records";
  $("preview-content").className = "preview-content empty";
  $("preview-content").textContent = "Loading preview";
  $("preview-modal").hidden = false;
  const preview = await api(`${clusterBase()}/databases/${encodeURIComponent(state.selectedDatabase)}/tables/${encodeURIComponent(state.selectedTable)}/preview?limit=100`);
  $("preview-meta").textContent = `${preview.rowCount} records from ${preview.subscriptions} bucket subscriptions`;
  renderResultTable("preview-content", preview);
}

function hidePreview() {
  $("preview-modal").hidden = true;
}

function showQuery() {
  if (!hasPrimaryKeys(state.selectedDetail)) return;
  $("query-title").textContent = `${state.selectedDatabase}.${state.selectedTable} Query`;
  $("query-meta").textContent = "Fill primary key values to run an exact lookup.";
  $("query-content").className = "preview-content empty";
  $("query-content").textContent = "Run a query to see rows.";
  renderPrimaryKeyFields(state.selectedDetail);
  $("query-modal").hidden = false;
}

function hideQuery() {
  $("query-modal").hidden = true;
}

function renderQueryColumns(detail) {
  const columns = ["", "_timestamp", "_offset", "_bucket", ...detail.columns.map((column) => column.name)];
  $("query-order-by").innerHTML = columns.map((column) => `<option value="${escapeHtml(column)}">${column ? escapeHtml(column) : "No order"}</option>`).join("");
}

function renderPrimaryKeyFields(detail) {
  const columnsByName = new Map((detail.columns || []).map((column) => [column.name, column]));
  $("query-pk-fields").innerHTML = detail.primaryKeys.map((key) => {
    const type = columnsByName.get(key)?.type || "primary key";
    return `<label class="pk-field"><span>${escapeHtml(key)} <code>${escapeHtml(type)}</code></span><input class="query-input" type="text" data-pk="${escapeHtml(key)}" autocomplete="off" placeholder="Required"></label>`;
  }).join("");
}

async function runQuery() {
  const where = buildPrimaryKeyWhere();
  if (!where) return;
  const params = new URLSearchParams({
    where,
    orderBy: $("query-order-by").value,
    orderDir: $("query-order-dir").value,
    limit: $("query-limit").value || "100",
  });
  $("query-meta").textContent = "Running query";
  $("query-content").className = "preview-content empty";
  $("query-content").textContent = "Loading rows";
  const result = await api(`${clusterBase()}/databases/${encodeURIComponent(state.selectedDatabase)}/tables/${encodeURIComponent(state.selectedTable)}/query?${params}`);
  $("query-meta").textContent = `${result.rowCount} rows, mode: ${result.mode}`;
  renderResultTable("query-content", result);
}

function buildPrimaryKeyWhere() {
  const fields = [...document.querySelectorAll("#query-pk-fields [data-pk]")];
  const invalid = fields.find((field) => field.value.trim() === "");
  fields.forEach((field) => field.classList.toggle("invalid", field.value.trim() === ""));
  if (invalid) {
    invalid.focus();
    $("query-meta").textContent = "Primary key values are required.";
    return "";
  }
  return fields.map((field) => `${sqlIdentifier(field.dataset.pk)} = ${sqlLiteral(field.value.trim())}`).join(" AND ");
}

function hasPrimaryKeys(detail) {
  return (detail?.primaryKeys || []).length > 0;
}

function sqlIdentifier(value) {
  return `\`${String(value).replaceAll("`", "``")}\``;
}

function sqlLiteral(value) {
  return `'${String(value).replaceAll("'", "''")}'`;
}

function renderResultTable(targetId, result) {
  const target = $(targetId);
  if (!result.rows?.length) {
    target.className = "preview-content empty";
    target.textContent = "No records found.";
    return;
  }
  const columns = result.columns.map((column) => column.name);
  target.className = "preview-content";
  target.innerHTML = `
    <div class="result-shell">
      <div class="result-table-wrap">
        <table class="preview-table">
          <thead><tr>${columns.map((column) => `<th>${escapeHtml(column)}</th>`).join("")}</tr></thead>
          <tbody>${result.rows.map((row, index) => `<tr data-row-index="${index}" class="${index === 0 ? "selected" : ""}">${columns.map((column) => previewCell(row[column])).join("")}</tr>`).join("")}</tbody>
        </table>
      </div>
      <aside class="row-inspector"><div class="inspector-header">Selected row</div><pre class="inspector-body">${escapeHtml(JSON.stringify(result.rows[0], null, 2))}</pre></aside>
    </div>`;
  target.querySelectorAll("[data-row-index]").forEach((rowElement) => {
    rowElement.addEventListener("click", () => {
      target.querySelectorAll("[data-row-index]").forEach((element) => element.classList.remove("selected"));
      rowElement.classList.add("selected");
      target.querySelector(".inspector-body").textContent = JSON.stringify(result.rows[Number(rowElement.dataset.rowIndex)], null, 2);
    });
  });
}

function previewCell(value) {
  const display = value === null || value === undefined ? "NULL" : String(value);
  const className = value === null || value === undefined ? " class=\"cell-null\"" : "";
  return `<td${className} title="${escapeHtml(display)}">${escapeHtml(display)}</td>`;
}

function openForm(title, subtitle, html, onSubmit) {
  $("form-title").textContent = title;
  $("form-subtitle").textContent = subtitle;
  $("dynamic-form").innerHTML = html;
  $("dynamic-form").onsubmit = async (event) => {
    event.preventDefault();
    await onSubmit(new FormData(event.target));
    hideForm();
  };
  $("form-modal").hidden = false;
}

function hideForm() {
  $("form-modal").hidden = true;
  $("dynamic-form").onsubmit = null;
}

function openClusterForm() {
  openClusterEditor();
}

function openEditClusterForm() {
  const cluster = state.clusters.find((item) => item.id === state.selectedClusterId);
  if (!cluster) {
    return;
  }
  openClusterEditor(cluster);
}

function openClusterEditor(cluster = null) {
  const editing = Boolean(cluster);
  openForm(editing ? "Edit cluster" : "Add cluster", editing ? "Update cluster connection settings." : "Connect to another Apache Fluss cluster.", `
    <label>Name<input name="name" required placeholder="Production" value="${escapeHtml(cluster?.name || "")}"></label>
    <label>Bootstrap servers<input name="bootstrapServers" required placeholder="host:9123" value="${escapeHtml(cluster?.bootstrapServers || "")}"></label>
    <label>Security protocol<input name="securityProtocol" value="${escapeHtml(cluster?.securityProtocol || "")}"></label>
    <label>SASL mechanism<input name="saslMechanism" value="${escapeHtml(cluster?.saslMechanism || "")}"></label>
    <label>SASL username<input name="saslUsername" value="${escapeHtml(cluster?.saslUsername || "")}"></label>
    <label>SASL password<input name="saslPassword" type="password" placeholder="${editing ? "Leave blank to keep current password" : ""}"></label>
    ${editing ? `<p class="form-note">Password is hidden for safety. Leave it blank to keep the saved password.</p>` : ""}
    <button type="submit">Create cluster</button>
  `, async (form) => {
    const payload = Object.fromEntries(form);
    if (editing && !payload.saslPassword) {
      delete payload.saslPassword;
    }
    await api(editing ? `/api/clusters/${encodeURIComponent(cluster.id)}` : "/api/clusters", {
      method: editing ? "PATCH" : "POST",
      body: JSON.stringify(payload),
    });
    if (editing) {
      delete state.clusterStatuses[cluster.id];
      state.selectedClusterId = cluster.id;
    }
    await loadClusters();
  });
  $("dynamic-form").querySelector("button[type='submit']").textContent = editing ? "Save changes" : "Create cluster";
}

function openDatabaseForm() {
  openForm("Create database", "Create a Fluss database in the selected cluster.", `
    <label>Name<input name="name" required placeholder="database_name"></label>
    <label>Comment<input name="comment"></label>
    <label>Custom properties<textarea name="customProperties" placeholder="key=value&#10;owner=data"></textarea></label>
    <button type="submit">Create database</button>
  `, async (form) => {
    await api(`${clusterBase()}/databases`, {
      method: "POST",
      body: JSON.stringify({
        name: form.get("name"),
        comment: form.get("comment"),
        customProperties: keyValues(form.get("customProperties")),
      }),
    });
    await loadClusterMetadata();
  });
}

function openTableForm() {
  openForm("Create table", `Create a table in ${state.selectedDatabase}.`, `
    <label>Name<input name="name" required placeholder="table_name"></label>
    <label>Columns<textarea name="columns" required placeholder="id STRING identifier&#10;ts TIMESTAMP(3)"></textarea></label>
    <label>Primary keys<input name="primaryKeys" placeholder="id"></label>
    <label>Partition keys<input name="partitionKeys" placeholder="dt"></label>
    <label>Bucket keys<input name="bucketKeys" placeholder="id"></label>
    <label>Bucket count<input name="bucketCount" type="number" min="1" value="1"></label>
    <label>Comment<input name="comment"></label>
    <label>Properties<textarea name="properties" placeholder="bucket.num=1"></textarea></label>
    <button type="submit">Create table</button>
  `, async (form) => {
    await api(`${clusterBase()}/databases/${encodeURIComponent(state.selectedDatabase)}/tables`, {
      method: "POST",
      body: JSON.stringify({
        name: form.get("name"),
        columns: parseColumns(form.get("columns")),
        primaryKeys: csv(form.get("primaryKeys")),
        partitionKeys: csv(form.get("partitionKeys")),
        bucketKeys: csv(form.get("bucketKeys")),
        bucketCount: Number(form.get("bucketCount")) || null,
        comment: form.get("comment"),
        properties: keyValues(form.get("properties")),
      }),
    });
    await loadTables(state.selectedDatabase);
  });
}

function openEditTableForm() {
  openForm("Edit table", `Alter ${state.selectedDatabase}.${state.selectedTable}.`, `
    <label>Add columns<textarea name="addColumns" placeholder="new_col STRING comment"></textarea></label>
    <label>Modify columns<textarea name="modifyColumns" placeholder="col_name BIGINT comment"></textarea></label>
    <label>Rename columns<textarea name="renameColumns" placeholder="old_name=new_name"></textarea></label>
    <label>Drop columns<input name="dropColumns" placeholder="old_col, other_col"></label>
    <label>Set options<textarea name="setOptions" placeholder="key=value"></textarea></label>
    <label>Reset options<input name="resetOptions" placeholder="key1,key2"></label>
    <button type="submit">Apply changes</button>
  `, async (form) => {
    const dropColumns = csv(form.get("dropColumns"));
    if (dropColumns.length && !confirm(`Drop columns ${dropColumns.join(", ")}?`)) return;
    await api(`${clusterBase()}/databases/${encodeURIComponent(state.selectedDatabase)}/tables/${encodeURIComponent(state.selectedTable)}`, {
      method: "PATCH",
      body: JSON.stringify({
        addColumns: parseColumns(form.get("addColumns")).map((column) => ({ ...column, first: false })),
        modifyColumns: parseColumns(form.get("modifyColumns")).map((column) => ({ ...column, first: false })),
        renameColumns: parseRenameColumns(form.get("renameColumns")),
        dropColumns,
        setOptions: keyValues(form.get("setOptions")),
        resetOptions: csv(form.get("resetOptions")),
      }),
    });
    await selectTable(state.selectedTable);
  });
}

async function deleteCluster() {
  if (!state.selectedClusterId) return;
  const cluster = state.clusters.find((item) => item.id === state.selectedClusterId);
  if (!confirm(`Delete cluster ${cluster?.name || state.selectedClusterId}?`)) return;
  await api(`/api/clusters/${encodeURIComponent(state.selectedClusterId)}`, { method: "DELETE" });
  state.selectedClusterId = null;
  await loadClusters();
}

async function deleteDatabase() {
  if (!state.selectedDatabase) return;
  if (state.selectedDatabase === DEFAULT_DATABASE) {
    throw new Error("The default database 'fluss' is required for cluster operation and cannot be deleted.");
  }
  if (!confirm(`Delete database ${state.selectedDatabase}?`)) return;
  await api(`${clusterBase()}/databases/${encodeURIComponent(state.selectedDatabase)}?ignoreIfNotExists=true&cascade=false`, { method: "DELETE" });
  state.selectedDatabase = null;
  await loadClusterMetadata();
}

async function deleteTable() {
  if (!state.selectedTable || !confirm(`Delete table ${state.selectedDatabase}.${state.selectedTable}?`)) return;
  await api(`${clusterBase()}/databases/${encodeURIComponent(state.selectedDatabase)}/tables/${encodeURIComponent(state.selectedTable)}?ignoreIfNotExists=true`, { method: "DELETE" });
  state.selectedTable = null;
  await loadTables(state.selectedDatabase);
}

function parseColumns(value) {
  return String(value || "").split(/\n+/).map((line) => line.trim()).filter(Boolean).map((line) => {
    const [name, type, ...comment] = line.split(/\s+/);
    return { name, type, comment: comment.join(" ") };
  });
}

function parseRenameColumns(value) {
  return String(value || "").split(/\n+/).map((line) => line.trim()).filter(Boolean).map((line) => {
    const [oldName, newName] = line.split("=").map((part) => part.trim());
    return { oldName, newName };
  });
}

function keyValues(value) {
  return Object.fromEntries(String(value || "").split(/\n+/).map((line) => line.trim()).filter(Boolean).map((line) => {
    const index = line.indexOf("=");
    return index < 0 ? [line, ""] : [line.slice(0, index).trim(), line.slice(index + 1).trim()];
  }));
}

function csv(value) {
  return String(value || "").split(",").map((item) => item.trim()).filter(Boolean);
}

function friendlyError(error, context = {}) {
  const detail = String(error?.message || error || "Unknown error");
  const cluster = context.clusterId
    ? state.clusters.find((item) => item.id === context.clusterId)
    : null;
  const endpoint = endpointFromError(detail) || cluster?.bootstrapServers || "the selected cluster";
  if (/NetworkException|Disconnected from node|Connection refused|Failed to connect|connect timed out|TimeoutException|timeout/i.test(detail)) {
    return {
      summary: "Cluster connection failed",
      title: "Unable to connect to the cluster",
      message: `Could not reach ${endpoint}. Check that the Fluss cluster is running and the bootstrap address is correct.`,
      detail,
    };
  }
  if (/SASL|LoginException|Authentication|Unauthorized|permission|forbidden/i.test(detail)) {
    return {
      summary: "Authentication failed",
      title: "Cluster authentication failed",
      message: "Check the security protocol, SASL mechanism, username, and password for this cluster.",
      detail,
    };
  }
  if (/not found|does not exist|NoSuch/i.test(detail)) {
    return {
      summary: "Resource not found",
      title: "The requested resource was not found",
      message: "Refresh metadata and make sure the selected database or table still exists.",
      detail,
    };
  }
  return {
    summary: "Operation failed",
    title: "Something went wrong",
    message: stripJavaPrefix(detail),
    detail,
  };
}

function endpointFromError(message) {
  const match = message.match(/(?:node|host|server)\s+([a-zA-Z0-9._-]+:\d+)/i)
    || message.match(/([a-zA-Z0-9._-]+:\d+)/);
  return match?.[1] || "";
}

function stripJavaPrefix(message) {
  return message
    .replace(/^([a-zA-Z_$][\w$]*\.)+[A-Za-z_$][\w$]*Exception:\s*/, "")
    .replace(/^([a-zA-Z_$][\w$]*\.)+[A-Za-z_$][\w$]*:\s*/, "")
    .trim();
}

function errorBlock(friendly) {
  return `
    <div class="error-card" title="${escapeHtml(friendly.detail)}">
      <strong>${escapeHtml(friendly.title)}</strong>
      <span>${escapeHtml(friendly.message)}</span>
    </div>
  `;
}

function showError(error) {
  if (error.authRequired) {
    return;
  }
  const friendly = friendlyError(error);
  setStatus("Disconnected", true);
  $("cluster-scale").className = "cluster-scale empty";
  $("cluster-scale").innerHTML = errorBlock(friendly);
}

function showTableError(error) {
  const friendly = friendlyError(error);
  $("table-count").textContent = "Error";
  $("table-list").className = "table-list error";
  $("table-list").textContent = friendly.message;
  $("table-list").title = friendly.detail;
}

function showDetailError(error) {
  const friendly = friendlyError(error);
  $("detail-badge").textContent = "Error";
  $("detail").className = "detail error";
  $("detail").textContent = friendly.message;
  $("detail").title = friendly.detail;
}

function showPreviewError(error) {
  const friendly = friendlyError(error);
  $("preview-meta").textContent = "Preview failed";
  $("preview-content").className = "preview-content error";
  $("preview-content").textContent = friendly.message;
  $("preview-content").title = friendly.detail;
  $("preview-modal").hidden = false;
}

function showQueryError(error) {
  const friendly = friendlyError(error);
  $("query-meta").textContent = "Query failed";
  $("query-content").className = "preview-content error";
  $("query-content").textContent = friendly.message;
  $("query-content").title = friendly.detail;
  $("query-modal").hidden = false;
}

$("login-form").addEventListener("submit", login);
$("logout").addEventListener("click", logout);
$("menu-toggle").addEventListener("click", () => document.body.classList.toggle("sidebar-collapsed"));
$("refresh").addEventListener("click", () => {
  const refresh = state.activePage === "cluster" || !state.selectedDatabase
    ? loadClusterMetadata()
    : loadTables(state.selectedDatabase);
  refresh.catch(showError);
});
$("cluster-nav").addEventListener("click", showClusterPage);
$("new-cluster").addEventListener("click", openClusterForm);
$("edit-cluster").addEventListener("click", openEditClusterForm);
$("delete-cluster").addEventListener("click", () => deleteCluster().catch(showError));
$("new-database").addEventListener("click", openDatabaseForm);
$("new-table").addEventListener("click", openTableForm);
$("delete-database").addEventListener("click", () => deleteDatabase().catch(showTableError));
$("delete-table").addEventListener("click", () => deleteTable().catch(showDetailError));
$("edit-table").addEventListener("click", openEditTableForm);
$("show-ddl").addEventListener("click", showDdl);
$("show-preview").addEventListener("click", () => showPreview().catch(showPreviewError));
$("show-query").addEventListener("click", showQuery);
$("close-ddl").addEventListener("click", hideDdl);
$("close-preview").addEventListener("click", hidePreview);
$("close-query").addEventListener("click", hideQuery);
$("close-form").addEventListener("click", hideForm);
$("query-form").addEventListener("submit", (event) => {
  event.preventDefault();
  runQuery().catch(showQueryError);
});
$("ddl-modal").querySelector("[data-close-ddl]").addEventListener("click", hideDdl);
$("preview-modal").querySelector("[data-close-preview]").addEventListener("click", hidePreview);
$("query-modal").querySelector("[data-close-query]").addEventListener("click", hideQuery);
$("form-modal").querySelector("[data-close-form]").addEventListener("click", hideForm);
$("table-search").addEventListener("input", (event) => {
  state.tableFilter = event.target.value;
  renderTables();
});
document.addEventListener("keydown", (event) => {
  if (event.key === "Escape") {
    hideDdl(); hidePreview(); hideQuery(); hideForm();
  }
});

initialize();

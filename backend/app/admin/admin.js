const state = {
  overview: null,
  refreshTimer: null,
  selectedShelfId: null,
  books: [],
  selectedBook: null,
  bookEdit: null,
  bookRequest: 0,
  passwordChangeRequired: false,
};
const $ = (selector) => document.querySelector(selector);
const COVER_MAX_BYTES = 8 * 1024 * 1024;
const COVER_TYPES = new Set(["image/jpeg", "image/png"]);

async function api(path, options = {}) {
  const method = options.method || "GET";
  const headers = new Headers(options.headers || {});
  if (options.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
  if (!["GET", "HEAD", "OPTIONS"].includes(method)) headers.set("X-Page-Shelf-Admin", "1");
  const response = await fetch(`/api/v1/admin${path}`, { ...options, method, headers, credentials: "same-origin" });
  if (response.status === 401) {
    showLogin();
    throw new Error("登录已失效，请重新登录");
  }
  if (!response.ok) {
    let message = `请求失败：HTTP ${response.status}`;
    try { message = (await response.json()).detail || message; } catch (_) { /* response is not JSON */ }
    throw new Error(message);
  }
  if (response.status === 204) return null;
  return response.json();
}

function escapeHtml(value) {
  return String(value ?? "").replace(/[&<>'"]/g, (character) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;",
  })[character]);
}

function formatBytes(value) {
  if (value == null) return "—";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let size = Number(value);
  let index = 0;
  while (size >= 1024 && index < units.length - 1) { size /= 1024; index += 1; }
  return `${size >= 10 || index === 0 ? size.toFixed(0) : size.toFixed(1)} ${units[index]}`;
}

function formatDate(value) {
  if (!value) return "尚未扫描";
  return new Intl.DateTimeFormat("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" }).format(new Date(value));
}

function splitModeLabel(mode, format) {
  if (format === "pdf") return "按页阅读";
  return ({
    auto: "智能识别",
    source: "EPUB/MOBI 原始目录",
    strict: "严格章节标题",
    expanded: "扩展标题识别",
    fixed: "固定字数",
    single: "整本单章",
  })[mode] || mode;
}

function bookFilename(book) {
  return book.filename || String(book.file_path || "").split(/[\\/]/).pop() || "未知文件";
}

function bookDirectory(book) {
  if (book.directory) return book.directory;
  const path = String(book.file_path || "");
  const separator = Math.max(path.lastIndexOf("/"), path.lastIndexOf("\\"));
  return separator >= 0 ? path.slice(0, separator) : "";
}

function bookLocation(book) {
  if (book.file_path) return book.file_path;
  const directory = bookDirectory(book);
  const filename = bookFilename(book);
  if (!directory) return filename;
  const separator = directory.includes("\\") ? "\\" : "/";
  return `${directory}${/[\\/]$/.test(directory) ? "" : separator}${filename}`;
}

function coverSourceLabel(source, status) {
  if (status && status !== "ready" && !source) return "封面不可用";
  return ({
    manual: "自定义封面",
    custom: "自定义封面",
    uploaded: "自定义封面",
    epub: "EPUB 内置封面",
    mobi: "MOBI 内置封面",
    pdf: "PDF 首页封面",
    embedded: "书籍内置封面",
    automatic: "自动封面",
    generated: "自动生成封面",
    cached: "缓存封面",
  })[source] || (source ? "当前封面" : "暂无封面");
}

function isManualCover(book) {
  return ["manual", "custom", "uploaded"].includes(book.cover_source);
}

function bookCoverUrl(book) {
  if (!book.cover_url) return "";
  if (!book._coverNonce) return book.cover_url;
  return `${book.cover_url}${book.cover_url.includes("?") ? "&" : "?"}v=${book._coverNonce}`;
}

let toastTimer;
function toast(message, type = "success") {
  const element = $("#toast");
  element.textContent = message;
  element.className = `toast show ${type === "error" ? "error" : ""}`;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { element.className = "toast"; }, 3600);
}

function showLogin() {
  $("#login-view").hidden = false;
  $("#dashboard-view").hidden = true;
  clearInterval(state.refreshTimer);
  setTimeout(() => $("#admin-password").focus(), 50);
}

function showDashboard(startRefresh = true) {
  $("#login-view").hidden = true;
  $("#dashboard-view").hidden = false;
  clearInterval(state.refreshTimer);
  if (startRefresh) state.refreshTimer = setInterval(() => loadOverview(false), 15000);
}

function openPasswordDialog(required = false) {
  state.passwordChangeRequired = required;
  const dialog = $("#password-dialog");
  const form = $("#password-form");
  form.reset();
  form.dataset.required = String(required);
  $("#password-required-note").hidden = !required;
  $("#password-dialog-title").textContent = required ? "设置新的管理密码" : "修改管理密码";
  $("#password-error").textContent = "";
  if (!dialog.open) dialog.showModal();
  setTimeout(() => $("#current-admin-password").focus(), 50);
}

function statusLabel(status) {
  return ({ idle: "等待中", scanning: "扫描中", warning: "有警告", error: "扫描失败" })[status] || status;
}

function summaryText(shelf) {
  if (shelf.last_scan_error) return shelf.last_scan_error;
  const summary = shelf.last_scan_summary;
  if (!summary) return "等待首次扫描";
  return `上次：新增 ${summary.imported || 0} · 更新 ${summary.updated || 0} · 移除 ${summary.removed || 0} · 失败 ${(summary.failures || []).length}`;
}

function renderShelves(shelves) {
  const target = $("#shelf-list");
  if (!shelves.length) {
    target.innerHTML = `<div class="empty-state"><strong>还没有书架</strong><span>先登记存储位置，再创建第一个书架。</span></div>`;
    return;
  }
  target.innerHTML = shelves.map((shelf) => `
    <article class="shelf-card" data-shelf-id="${escapeHtml(shelf.id)}" data-pin-configured="${shelf.pin_configured}">
      <div class="shelf-card-head">
        <div><h3>${escapeHtml(shelf.name)}</h3><p class="shelf-path">${escapeHtml(shelf.resolved_path)}</p></div>
        <span class="status status-${escapeHtml(shelf.scan_status)}">${escapeHtml(statusLabel(shelf.scan_status))}</span>
      </div>
      <div>
        <div class="shelf-stats">
          <div><strong>${shelf.book_count}</strong><span>书籍</span></div>
          <div><strong>${formatBytes(shelf.total_bytes)}</strong><span>容量</span></div>
          <div><strong>${formatDate(shelf.last_scan_completed_at)}</strong><span>最近扫描</span></div>
        </div>
        <div class="shelf-controls">
          <label class="auto-control"><input class="auto-toggle" type="checkbox" ${shelf.auto_scan_enabled ? "checked" : ""}><span>自动扫描</span></label>
          <label class="interval-control"><span>每</span><input class="interval-input" type="number" min="1" max="10080" value="${shelf.scan_interval_minutes}"><span>分钟</span></label>
        </div>
        <div class="shelf-privacy">
          <label class="check-row"><input class="hidden-toggle" type="checkbox" ${shelf.is_hidden ? "checked" : ""}><span>隐藏书架</span></label>
          <label class="password-control">
            <span>四位访问密码${shelf.pin_configured ? '<em>已设置</em>' : ""}</span>
            <input class="pin-input" type="password" inputmode="numeric" pattern="[0-9]{4}" minlength="4" maxlength="4" autocomplete="new-password" placeholder="${shelf.pin_configured ? "输入新密码可直接修改" : "输入四位数字即可设置"}">
            <small>${shelf.pin_configured ? "留空保持不变，无需输入旧密码" : "设置后 App 将要求输入密码"}</small>
          </label>
          <label class="check-row clear-pin-control" ${shelf.pin_configured ? "" : "hidden"}><input class="clear-pin-toggle" type="checkbox"><span>清除现有密码</span></label>
        </div>
        <p class="scan-note">${escapeHtml(summaryText(shelf))}</p>
      </div>
      <div class="card-actions">
        <button class="button button-quiet view-books" type="button">查看图书</button>
        <button class="button button-outline scan-shelf" type="button" ${shelf.scan_status === "scanning" ? "disabled" : ""}>立即扫描</button>
        <button class="button button-quiet save-shelf" type="button">保存策略</button>
        <button class="button button-danger delete-shelf" type="button">移除书架</button>
      </div>
    </article>`).join("");
}

function syncBookShelfFilter(shelves) {
  const select = $("#book-shelf-filter");
  const previous = state.selectedShelfId;
  select.innerHTML = shelves.map((shelf) =>
    `<option value="${escapeHtml(shelf.id)}" title="${escapeHtml(shelf.name)} · ${shelf.book_count} 本">${escapeHtml(shelf.name)} · ${shelf.book_count} 本</option>`
  ).join("");
  if (!shelves.length) {
    state.selectedShelfId = null;
    state.books = [];
    select.disabled = true;
    select.title = "";
    $("#book-shelf-selection").textContent = "";
    renderBooks();
    return;
  }
  select.disabled = false;
  const next = shelves.some((shelf) => shelf.id === previous) ? previous : shelves[0].id;
  select.value = next;
  updateBookShelfCaption();
  if (state.selectedShelfId !== next) loadShelfBooks(next);
}

function updateBookShelfCaption() {
  const select = $("#book-shelf-filter");
  const text = select.selectedOptions[0]?.textContent || "";
  select.title = text;
  $("#book-shelf-selection").textContent = text;
}

function renderBooks() {
  const target = $("#book-list");
  const query = $("#book-search").value.trim().toLocaleLowerCase("zh-CN");
  const books = state.books.filter((book) =>
    !query || [book.title, book.author, book.file_path].some((value) =>
      String(value || "").toLocaleLowerCase("zh-CN").includes(query)
    )
  );
  if (!state.selectedShelfId) {
    target.innerHTML = `<div class="empty-state"><strong>还没有可查看的书架</strong><span>创建并扫描书架后，书籍会在这里出现。</span></div>`;
    return;
  }
  if (!books.length) {
    target.innerHTML = `<div class="empty-state"><strong>${query ? "没有匹配的书籍" : "这个书架还是空的"}</strong><span>${query ? "换一个关键词试试。" : "立即扫描书架以发现 TXT、EPUB、MOBI 和 PDF 文件。"}</span></div>`;
    return;
  }
  target.innerHTML = books.map((book) => {
    const filename = bookFilename(book);
    const coverUrl = bookCoverUrl(book);
    const extent = book.format === "pdf" ? `${book.page_count || 0} 页` : `${book.chapter_count || 0} 章`;
    const warning = book.parse_warnings?.[0];
    return `
      <article class="book-row" data-book-id="${escapeHtml(book.id)}">
        <figure class="book-cover format-${escapeHtml(book.format)}">
          <span class="book-cover-fallback" aria-hidden="true"><b>页</b><small>${escapeHtml(book.format.toUpperCase())}</small></span>
          ${coverUrl ? `<img class="book-cover-image" src="${escapeHtml(coverUrl)}" alt="" loading="lazy" decoding="async">` : ""}
        </figure>
        <div class="book-main">
          <div class="book-title-line"><h3 title="${escapeHtml(book.title)}">${escapeHtml(book.title)}</h3><span>${escapeHtml(extent)}</span></div>
          <p>${escapeHtml(book.author || "作者未知")} · <code>${escapeHtml(filename)}</code></p>
          <span class="book-cover-source">${escapeHtml(coverSourceLabel(book.cover_source, book.cover_status))}</span>
          ${warning ? `<small class="book-warning">${escapeHtml(warning)}</small>` : ""}
        </div>
        <div class="book-strategy">
          <span>当前方式</span>
          <strong>${escapeHtml(splitModeLabel(book.chapter_split_mode, book.format))}</strong>
          ${book.format === "pdf" ? `<small>PDF 不参与章节体系</small>` : ""}
          <div class="book-row-actions">
            <button class="button button-quiet edit-book" type="button">编辑书籍</button>
            ${book.format === "pdf" ? "" : `<button class="button button-outline configure-split" type="button">拆分设置</button>`}
          </div>
        </div>
      </article>`;
  }).join("");
  target.querySelectorAll(".book-cover-image").forEach((image) => {
    image.addEventListener("error", () => { image.hidden = true; });
  });
}

async function loadShelfBooks(shelfId, notify = false) {
  state.selectedShelfId = shelfId;
  const request = ++state.bookRequest;
  const target = $("#book-list");
  target.setAttribute("aria-busy", "true");
  target.innerHTML = `<div class="book-loading" aria-label="正在加载图书"><span></span><span></span><span></span></div>`;
  try {
    const books = await api(`/shelves/${shelfId}/books`);
    if (request !== state.bookRequest) return;
    state.books = books;
    renderBooks();
    if (notify) toast(`已载入 ${books.length} 本书`);
  } catch (error) {
    if (request !== state.bookRequest) return;
    state.books = [];
    target.innerHTML = `<div class="empty-state error-state"><strong>图书加载失败</strong><span>${escapeHtml(error.message)}</span><button class="button button-quiet retry-books" type="button">重试</button></div>`;
  } finally {
    if (request === state.bookRequest) target.removeAttribute("aria-busy");
  }
}

function renderStorage(overview) {
  const registeredPaths = new Set(overview.storage_locations.map((location) => location.path));
  $("#root-list").innerHTML = overview.storage_roots.map((root) => `
    <div class="root-item" data-root-path="${escapeHtml(root.path)}">
      <div><code>${escapeHtml(root.path)}</code><small>${root.exists && root.writable ? "可读写" : "不可用"} · 剩余 ${formatBytes(root.free_bytes)}</small></div>
      <div class="root-actions">
        <span class="status status-${root.exists && root.writable ? "idle" : "error"}">${root.exists && root.writable ? "已挂载" : "异常"}</span>
        ${registeredPaths.has(root.path) ? '<span class="registered-label">已登记</span>' : `<button class="button button-quiet register-root" type="button" ${root.exists && root.writable ? "" : "disabled"}>登记</button>`}
        <button class="button button-danger remove-root" type="button" ${registeredPaths.has(root.path) ? 'disabled title="请先移除该路径下的书架和存储登记"' : ""}>移除</button>
      </div>
    </div>`).join("");
  $("#storage-list").innerHTML = overview.storage_locations.length
    ? overview.storage_locations.map((location) => `
      <div class="storage-item" data-location-id="${escapeHtml(location.id)}"><div><strong>${escapeHtml(location.name)}</strong><code>${escapeHtml(location.path)}</code><small>${location.shelf_count} 个书架</small></div><button class="text-action delete-storage" type="button" ${location.shelf_count ? "disabled" : ""}>移除</button></div>`).join("")
    : `<div class="empty-state"><strong>尚未登记</strong><span>从左侧授权范围中登记一个存储位置。</span></div>`;

  const select = $("#shelf-location");
  select.innerHTML = overview.storage_locations.map((location) => `<option value="${escapeHtml(location.id)}">${escapeHtml(location.name)} · ${escapeHtml(location.path)}</option>`).join("");
  $("#storage-path").placeholder = overview.storage_roots[0]?.path || "/library";
}

function renderOverview(overview) {
  state.overview = overview;
  $("#metric-shelves").textContent = overview.shelves.length;
  $("#metric-books").textContent = overview.total_books;
  $("#metric-size").textContent = formatBytes(overview.total_bytes);
  $("#metric-scanning").textContent = overview.scanning_count;
  $("#last-refreshed").textContent = `更新于 ${new Date().toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit", second: "2-digit" })}`;
  renderShelves(overview.shelves);
  syncBookShelfFilter(overview.shelves);
  renderStorage(overview);
}

async function loadOverview(notify = false) {
  try {
    const overview = await api("/overview");
    showDashboard();
    renderOverview(overview);
    if (notify) toast("状态已刷新");
  } catch (error) {
    if (!$("#dashboard-view").hidden) toast(error.message, "error");
  }
}

$("#login-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const button = event.currentTarget.querySelector("button[type=submit]");
  const error = $("#login-error");
  error.textContent = "";
  button.disabled = true;
  try {
    await api("/session", { method: "POST", body: JSON.stringify({ password: $("#admin-password").value }) });
    $("#admin-password").value = "";
    const status = await api("/status");
    if (status.password_change_required) {
      showDashboard(false);
      openPasswordDialog(true);
    } else {
      await loadOverview();
    }
  } catch (failure) {
    error.textContent = failure.message;
  } finally { button.disabled = false; }
});

$("#logout-button").addEventListener("click", async () => {
  try { await api("/session", { method: "DELETE" }); } catch (_) { /* session may already be gone */ }
  showLogin();
});
$("#refresh-button").addEventListener("click", () => loadOverview(true));
$("#change-password-button").addEventListener("click", () => openPasswordDialog(false));
$("#password-dialog").addEventListener("cancel", (event) => {
  if (state.passwordChangeRequired) event.preventDefault();
});
$("#password-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const currentPassword = $("#current-admin-password").value;
  const newPassword = $("#new-admin-password").value;
  const confirmation = $("#confirm-admin-password").value;
  const error = $("#password-error");
  const button = $("#password-submit");
  error.textContent = "";
  if (newPassword !== confirmation) {
    error.textContent = "两次输入的新密码不一致";
    $("#confirm-admin-password").focus();
    return;
  }
  if (newPassword.trim().length < 8) {
    error.textContent = "新密码去除首尾空格后至少需要 8 位";
    $("#new-admin-password").focus();
    return;
  }
  if (newPassword === "112233") {
    error.textContent = "新密码不能继续使用默认密码 112233";
    $("#new-admin-password").focus();
    return;
  }
  button.disabled = true;
  button.textContent = "正在保存…";
  try {
    await api("/password", {
      method: "PUT",
      body: JSON.stringify({ current_password: currentPassword, new_password: newPassword }),
    });
    state.passwordChangeRequired = false;
    $("#password-dialog").close();
    await loadOverview();
    toast("管理密码已更新，其他设备需要使用新密码重新登录");
  } catch (failure) {
    error.textContent = failure.message;
  } finally {
    button.disabled = false;
    button.textContent = "保存新密码";
  }
});
$("#refresh-storage-button").addEventListener("click", async (event) => {
  event.currentTarget.disabled = true;
  try {
    await api("/storage-roots/refresh", { method: "POST" });
    await loadOverview();
    toast("存储授权已重新加载");
  } catch (error) { toast(error.message, "error"); }
  finally { event.currentTarget.disabled = false; }
});
$("#scan-all-button").addEventListener("click", async (event) => {
  event.currentTarget.disabled = true;
  try {
    const result = await api("/scan-all", { method: "POST" });
    toast(`扫描完成：新增 ${result.imported}，更新 ${result.updated}，移除 ${result.removed}`);
    await loadOverview();
    if (state.selectedShelfId) await loadShelfBooks(state.selectedShelfId);
  } catch (error) { toast(error.message, "error"); }
  finally { event.currentTarget.disabled = false; }
});

$("#add-storage-button").addEventListener("click", () => {
  $("#storage-error").textContent = "";
  if (!$("#storage-path").value) $("#storage-path").value = state.overview?.storage_roots[0]?.path || "";
  $("#storage-dialog").showModal();
});
$("#root-list").addEventListener("click", (event) => {
  const button = event.target.closest(".register-root, .remove-root");
  if (!button) return;
  const root = button.closest(".root-item").dataset.rootPath;
  if (button.classList.contains("remove-root")) {
    if (!confirm(`从当前授权列表移除 ${root}？NAS 目录和文件不会被删除。`)) return;
    button.disabled = true;
    api("/storage-roots", { method: "DELETE", body: JSON.stringify({ path: root }) })
      .then(() => loadOverview())
      .then(() => toast("授权路径已移除"))
      .catch((error) => { button.disabled = false; toast(error.message, "error"); });
    return;
  }
  const leaf = root.split("/").filter(Boolean).pop() || "NAS";
  $("#storage-error").textContent = "";
  $("#storage-path").value = root;
  $("#storage-name").value = `${leaf}存储`;
  $("#storage-dialog").showModal();
});
$("#storage-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  $("#storage-error").textContent = "";
  try {
    await api("/storage-locations", { method: "POST", body: JSON.stringify({
      name: $("#storage-name").value,
      path: $("#storage-path").value,
      create_directory: $("#storage-create").checked,
    }) });
    $("#storage-dialog").close();
    form.reset();
    toast("存储位置已登记");
    await loadOverview();
  } catch (error) { $("#storage-error").textContent = error.message; }
});

$("#add-shelf-button").addEventListener("click", () => {
  if (!state.overview?.storage_locations.length) { toast("请先登记一个存储位置", "error"); return; }
  $("#shelf-error").textContent = "";
  syncShelfRootMode();
  syncShelfPrivacyMode();
  $("#shelf-dialog").showModal();
});
function syncShelfRootMode() {
  const useRoot = $("#shelf-root").checked;
  const folder = $("#shelf-folder");
  folder.disabled = useRoot;
  if (useRoot) {
    if (folder.value && folder.value !== ".") folder.dataset.previousValue = folder.value;
    folder.value = ".";
  } else {
    folder.value = folder.dataset.previousValue || "";
  }
}
$("#shelf-root").addEventListener("change", syncShelfRootMode);
function syncShelfPrivacyMode() {
  const hidden = $("#shelf-hidden").checked;
  const pin = $("#shelf-pin");
  pin.required = hidden;
  pin.placeholder = hidden ? "必填；请输入四位数字" : "可选；设置后 App 会要求输入";
}
$("#shelf-hidden").addEventListener("change", syncShelfPrivacyMode);
$("#shelf-name").addEventListener("input", (event) => {
  const folder = $("#shelf-folder");
  if ($("#shelf-root").checked) return;
  if (folder.dataset.edited === "true") return;
  folder.value = event.target.value.trim().replace(/[\\/:*?"<>|\s]+/g, "-").replace(/^-+|-+$/g, "");
});
$("#shelf-folder").addEventListener("input", (event) => { event.target.dataset.edited = "true"; });
$("#shelf-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  $("#shelf-error").textContent = "";
  try {
    if ($("#shelf-hidden").checked && !/^\d{4}$/.test($("#shelf-pin").value)) {
      throw new Error("隐藏书架必须设置四位数字访问密码");
    }
    await api("/shelves", { method: "POST", body: JSON.stringify({
      name: $("#shelf-name").value,
      storage_location_id: $("#shelf-location").value,
      relative_path: $("#shelf-folder").value,
      auto_scan_enabled: $("#shelf-auto").checked,
      scan_interval_minutes: Number($("#shelf-interval").value),
      scan_after_create: true,
      is_hidden: $("#shelf-hidden").checked,
      access_pin: $("#shelf-pin").value || null,
    }) });
    $("#shelf-dialog").close();
    form.reset();
    $("#shelf-folder").dataset.edited = "false";
    delete $("#shelf-folder").dataset.previousValue;
    syncShelfRootMode();
    syncShelfPrivacyMode();
    toast("书架已创建并完成首次扫描");
    await loadOverview();
  } catch (error) { $("#shelf-error").textContent = error.message; }
});

$("#shelf-list").addEventListener("click", async (event) => {
  const card = event.target.closest(".shelf-card");
  if (!card) return;
  const shelfId = card.dataset.shelfId;
  const button = event.target.closest("button");
  if (!button) return;
  button.disabled = true;
  let refreshBooks = false;
  try {
    if (button.classList.contains("scan-shelf")) {
      const result = await api(`/shelves/${shelfId}/scan`, { method: "POST" });
      toast(`扫描完成：发现 ${result.discovered} 个文件，失败 ${result.failed} 个`);
      refreshBooks = state.selectedShelfId === shelfId;
    } else if (button.classList.contains("view-books")) {
      $("#book-shelf-filter").value = shelfId;
      await loadShelfBooks(shelfId);
      $("#books").scrollIntoView({ behavior: "smooth", block: "start" });
    } else if (button.classList.contains("save-shelf")) {
      const hidden = card.querySelector(".hidden-toggle").checked;
      const pin = card.querySelector(".pin-input").value;
      const pinConfigured = card.dataset.pinConfigured === "true";
      const clearPin = card.querySelector(".clear-pin-toggle")?.checked === true;
      if (pin && !/^\d{4}$/.test(pin)) throw new Error("访问密码必须是四位数字");
      if (hidden && (clearPin || (!pinConfigured && !pin))) throw new Error("隐藏书架必须保留四位数字访问密码");
      const payload = {
        auto_scan_enabled: card.querySelector(".auto-toggle").checked,
        scan_interval_minutes: Number(card.querySelector(".interval-input").value),
        is_hidden: hidden,
      };
      if (clearPin) payload.access_pin = null;
      else if (pin) payload.access_pin = pin;
      await api(`/shelves/${shelfId}`, { method: "PATCH", body: JSON.stringify(payload) });
      toast("书架设置已保存");
    } else if (button.classList.contains("delete-shelf")) {
      if (!confirm("移除书架及其目录记录？NAS 中的原始文件不会被删除。")) return;
      await api(`/shelves/${shelfId}`, { method: "DELETE" });
      toast("书架已移除，原始文件仍保留在 NAS");
    }
    await loadOverview();
    if (refreshBooks) await loadShelfBooks(shelfId);
  } catch (error) { toast(error.message, "error"); }
  finally { button.disabled = false; }
});

$("#book-shelf-filter").addEventListener("change", (event) => {
  updateBookShelfCaption();
  loadShelfBooks(event.target.value);
});
$("#book-search").addEventListener("input", renderBooks);

function setBookCoverPreview(url, label) {
  const preview = $("#edit-cover-preview");
  const placeholder = $("#edit-cover-placeholder");
  if (url) {
    preview.src = url;
    preview.hidden = false;
    placeholder.hidden = true;
  } else {
    preview.removeAttribute("src");
    preview.hidden = true;
    placeholder.hidden = false;
  }
  $("#edit-cover-source").textContent = label;
}

function refreshBookResetControl(field) {
  const editing = state.bookEdit;
  if (!editing) return;
  const resetKey = field === "title" ? "resetTitle" : "resetAuthor";
  const input = $(`#edit-book-${field}`);
  const button = $(`#edit-reset-${field}`);
  const active = editing[resetKey];
  input.disabled = active;
  input.classList.toggle("automatic-field", active);
  button.classList.toggle("active", active);
  button.setAttribute("aria-pressed", String(active));
  button.textContent = active ? "取消恢复" : `恢复自动${field === "title" ? "书名" : "作者"}`;
}

function refreshBookCoverControl(book) {
  const editing = state.bookEdit;
  if (!editing) return;
  const removeButton = $("#edit-cover-remove");
  const hasExistingCover = Boolean(book.cover_url || book.cover_source);
  if (editing.removeCover) {
    removeButton.disabled = false;
    removeButton.textContent = "撤销封面更改";
    setBookCoverPreview("", isManualCover(book)
      ? "保存后恢复自动封面"
      : "保存后重新生成自动封面");
    return;
  }
  removeButton.disabled = false;
  removeButton.textContent = isManualCover(book)
    ? "恢复自动封面"
    : (hasExistingCover ? "清除当前封面" : "重新生成自动封面");
  if (editing.coverBase64) return;
  setBookCoverPreview(bookCoverUrl(book), coverSourceLabel(book.cover_source, book.cover_status));
}

function populateBookEditor(book) {
  state.selectedBook = book;
  state.bookEdit = {
    resetTitle: false,
    resetAuthor: false,
    coverBase64: null,
    coverFilename: null,
    removeCover: false,
  };
  $("#edit-book-form").reset();
  $("#edit-book-title").value = book.title || "";
  $("#edit-book-author").value = book.author || "";
  const filename = bookFilename(book);
  const location = bookLocation(book);
  $("#edit-book-filename").textContent = filename;
  $("#edit-book-filename").title = filename;
  $("#edit-book-location").textContent = location;
  $("#edit-book-location").title = location;
  $("#edit-cover-file").value = "";
  $("#edit-book-error").textContent = "";
  $("#edit-book-status").textContent = "";
  $("#edit-book-status").className = "form-status";
  refreshBookResetControl("title");
  refreshBookResetControl("author");
  refreshBookCoverControl(book);
}

function openBookEditor(book) {
  populateBookEditor(book);
  $("#edit-book-dialog").showModal();
  setTimeout(() => $("#edit-book-title").focus(), 50);
}

function toggleBookReset(field) {
  if (!state.bookEdit) return;
  const resetKey = field === "title" ? "resetTitle" : "resetAuthor";
  state.bookEdit[resetKey] = !state.bookEdit[resetKey];
  refreshBookResetControl(field);
  $("#edit-book-status").textContent = state.bookEdit[resetKey]
    ? `保存后恢复自动${field === "title" ? "书名" : "作者"}`
    : "已取消自动恢复";
  $("#edit-book-status").className = "form-status pending";
}

$("#edit-reset-title").addEventListener("click", () => toggleBookReset("title"));
$("#edit-reset-author").addEventListener("click", () => toggleBookReset("author"));

$("#edit-cover-file").addEventListener("change", (event) => {
  const file = event.target.files?.[0];
  const book = state.selectedBook;
  if (!file || !book || !state.bookEdit) return;
  $("#edit-book-error").textContent = "";
  if (!COVER_TYPES.has(file.type)) {
    event.target.value = "";
    $("#edit-book-error").textContent = "仅支持 JPEG 或 PNG 图片，不接受 SVG 等可执行格式。";
    return;
  }
  if (file.size > COVER_MAX_BYTES) {
    event.target.value = "";
    $("#edit-book-error").textContent = "封面文件不能超过 8 MB。";
    return;
  }
  const reader = new FileReader();
  reader.addEventListener("load", () => {
    if (typeof reader.result !== "string" || !reader.result.startsWith("data:image/")) {
      $("#edit-book-error").textContent = "无法读取这张图片，请换一个文件重试。";
      return;
    }
    const separator = reader.result.indexOf(",");
    if (separator < 0) {
      $("#edit-book-error").textContent = "图片编码无效，请换一个文件重试。";
      return;
    }
    state.bookEdit.coverBase64 = reader.result.slice(separator + 1);
    state.bookEdit.coverFilename = file.name;
    state.bookEdit.removeCover = false;
    setBookCoverPreview(reader.result, `待上传 · ${file.name}`);
    refreshBookCoverControl(book);
    $("#edit-book-status").textContent = "新封面已准备好，保存后生效。";
    $("#edit-book-status").className = "form-status pending";
  });
  reader.addEventListener("error", () => {
    $("#edit-book-error").textContent = "读取封面失败，请重新选择文件。";
  });
  reader.readAsDataURL(file);
});

$("#edit-cover-remove").addEventListener("click", () => {
  const book = state.selectedBook;
  if (!book || !state.bookEdit) return;
  if (state.bookEdit.coverBase64) {
    state.bookEdit.coverBase64 = null;
    state.bookEdit.coverFilename = null;
    state.bookEdit.removeCover = false;
    $("#edit-cover-file").value = "";
    refreshBookCoverControl(book);
    $("#edit-book-status").textContent = "已取消新封面，保留当前封面。";
    $("#edit-book-status").className = "form-status pending";
    return;
  }
  state.bookEdit.removeCover = !state.bookEdit.removeCover;
  $("#edit-cover-file").value = "";
  refreshBookCoverControl(book);
  $("#edit-book-status").textContent = state.bookEdit.removeCover ? "封面更改将在保存后生效。" : "已撤销封面更改。";
  $("#edit-book-status").className = "form-status pending";
});

$("#edit-cover-preview").addEventListener("error", () => {
  setBookCoverPreview("", "封面载入失败，可上传新封面或恢复自动封面");
});

$("#edit-book-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const book = state.selectedBook;
  const editing = state.bookEdit;
  if (!book || !editing) return;
  const title = $("#edit-book-title").value.trim();
  const author = $("#edit-book-author").value.trim();
  if (!editing.resetTitle && !title) {
    $("#edit-book-error").textContent = "书名不能为空；也可以选择恢复自动书名。";
    $("#edit-book-title").focus();
    return;
  }
  const button = $("#edit-book-submit");
  const form = event.currentTarget;
  $("#edit-book-error").textContent = "";
  $("#edit-book-status").textContent = "正在保存书目信息与封面…";
  $("#edit-book-status").className = "form-status pending";
  form.setAttribute("aria-busy", "true");
  button.disabled = true;
  button.textContent = "正在保存…";
  try {
    const payload = {
      reset_title: editing.resetTitle,
      reset_author: editing.resetAuthor,
      cover_base64: editing.coverBase64,
      cover_filename: editing.coverFilename,
      remove_cover: editing.removeCover,
    };
    if (!editing.resetTitle) payload.title = title;
    if (!editing.resetAuthor) payload.author = author || null;
    const updated = await api(`/books/${book.id}/metadata`, {
      method: "PATCH",
      body: JSON.stringify(payload),
    });
    const updatedBook = { ...book, ...updated, _coverNonce: Date.now() };
    state.books = state.books.map((item) => item.id === updatedBook.id ? updatedBook : item);
    renderBooks();
    populateBookEditor(updatedBook);
    $("#edit-book-status").textContent = "保存成功，书籍列表已更新。";
    $("#edit-book-status").className = "form-status success";
    toast(`《${updatedBook.title}》的书目信息已更新`);
  } catch (error) {
    $("#edit-book-error").textContent = error.message;
    $("#edit-book-status").textContent = "保存失败，当前输入仍保留。";
    $("#edit-book-status").className = "form-status error";
  } finally {
    form.removeAttribute("aria-busy");
    button.disabled = false;
    button.textContent = "保存书籍信息";
  }
});

$("#book-list").addEventListener("click", (event) => {
  const retry = event.target.closest(".retry-books");
  if (retry) {
    loadShelfBooks(state.selectedShelfId);
    return;
  }
  const editButton = event.target.closest(".edit-book");
  if (editButton) {
    const row = editButton.closest(".book-row");
    const book = state.books.find((item) => item.id === row.dataset.bookId);
    if (book) openBookEditor(book);
    return;
  }
  const button = event.target.closest(".configure-split");
  if (!button) return;
  const row = button.closest(".book-row");
  const book = state.books.find((item) => item.id === row.dataset.bookId);
  if (!book) return;
  state.selectedBook = book;
  $("#split-dialog-title").textContent = book.title;
  $("#split-book-meta").textContent = `${book.format.toUpperCase()} · ${book.chapter_count || 0} 章 · ${bookFilename(book)}`;
  $("#split-error").textContent = "";
  document.querySelectorAll(".epub-only").forEach((element) => { element.hidden = !["epub", "mobi"].includes(book.format); });
  const selectedMode = book.chapter_split_mode === "none" ? "auto" : book.chapter_split_mode;
  const radio = document.querySelector(`input[name="split-mode"][value="${selectedMode}"]`)
    || document.querySelector('input[name="split-mode"][value="auto"]');
  radio.checked = true;
  $("#split-segment-size").value = book.chapter_split_config?.segment_size || 12000;
  syncSplitMode();
  $("#split-dialog").showModal();
});

function syncSplitMode() {
  const mode = document.querySelector('input[name="split-mode"]:checked')?.value;
  $("#segment-size-row").hidden = mode !== "fixed";
  document.querySelectorAll(".split-option").forEach((option) => {
    option.classList.toggle("selected", option.querySelector("input").checked);
  });
}
document.querySelectorAll('input[name="split-mode"]').forEach((input) => input.addEventListener("change", syncSplitMode));
$("#split-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const book = state.selectedBook;
  if (!book) return;
  const button = $("#split-submit");
  const mode = document.querySelector('input[name="split-mode"]:checked').value;
  const segmentSize = Number($("#split-segment-size").value);
  $("#split-error").textContent = "";
  button.disabled = true;
  button.textContent = "正在重新拆分…";
  try {
    const updated = await api(`/books/${book.id}/chapter-split`, {
      method: "PATCH",
      body: JSON.stringify({ mode, segment_size: segmentSize }),
    });
    state.books = state.books.map((item) => item.id === updated.id ? updated : item);
    state.selectedBook = updated;
    $("#split-dialog").close();
    renderBooks();
    toast(`《${updated.title}》已重新拆分为 ${updated.chapter_count} 章`);
  } catch (error) {
    $("#split-error").textContent = error.message;
  } finally {
    button.disabled = false;
    button.textContent = "保存并重新拆分";
  }
});

$("#shelf-list").addEventListener("change", (event) => {
  const card = event.target.closest(".shelf-card");
  if (!card) return;
  if (event.target.classList.contains("clear-pin-toggle")) {
    const pin = card.querySelector(".pin-input");
    pin.disabled = event.target.checked;
    if (event.target.checked) pin.value = "";
  }
});

$("#storage-list").addEventListener("click", async (event) => {
  const button = event.target.closest(".delete-storage");
  if (!button) return;
  const item = button.closest(".storage-item");
  if (!confirm("移除这个存储位置登记？目录和文件不会被删除。")) return;
  try {
    await api(`/storage-locations/${item.dataset.locationId}`, { method: "DELETE" });
    toast("存储位置登记已移除");
    await loadOverview();
  } catch (error) { toast(error.message, "error"); }
});

function syncResetConfirmation() {
  $("#reset-submit").disabled = !$("#reset-understood").checked || $("#reset-confirmation").value !== "RESET";
}
$("#reset-button").addEventListener("click", () => {
  $("#reset-form").reset();
  $("#reset-error").textContent = "";
  syncResetConfirmation();
  $("#reset-dialog").showModal();
});
$("#reset-understood").addEventListener("change", syncResetConfirmation);
$("#reset-confirmation").addEventListener("input", syncResetConfirmation);
$("#reset-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const button = $("#reset-submit");
  button.disabled = true;
  $("#reset-error").textContent = "";
  try {
    const result = await api("/reset", { method: "POST", body: JSON.stringify({ confirmation: "RESET" }) });
    $("#reset-dialog").close();
    toast(`重置完成：清除 ${result.books_deleted} 本索引、${result.shelves_deleted} 个书架`);
    await loadOverview();
  } catch (error) {
    $("#reset-error").textContent = error.message;
    syncResetConfirmation();
  }
});

document.querySelectorAll(".dialog-close").forEach((button) => button.addEventListener("click", (event) => {
  event.preventDefault();
  button.closest("dialog").close();
}));
document.querySelectorAll("[data-close-dialog]").forEach((button) => button.addEventListener("click", () => {
  button.closest("dialog").close();
}));

(async function bootstrap() {
  try {
    const status = await api("/status");
    if (!status.authenticated) { showLogin(); return; }
    if (status.password_change_required) {
      showDashboard(false);
      openPasswordDialog(true);
      return;
    }
  } catch (_) { /* status endpoint is public and should remain available */ }
  await loadOverview();
})();

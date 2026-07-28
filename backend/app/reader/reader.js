"use strict";

const API_ROOT = "/api/v1";
const SESSION_KEY = "page-shelf.reader.session";
const PREFERENCES_KEY = "page-shelf.reader.preferences";
const LAST_SHELF_KEY = "page-shelf.reader.last-shelf";
const DEVICE_KEY = "page-shelf.reader.device-id";
const THEMES = new Set(["paper", "sepia", "dark"]);
const FONTS = new Set(["song", "kai", "sans"]);
const PDF_FORMAT = "pdf";

class ApiError extends Error {
  constructor(message, status, payload = null) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.payload = payload;
  }
}

const state = {
  token: null,
  shelves: [],
  activeShelfId: null,
  unlockedShelves: new Map(),
  shelfPins: new Map(),
  coverUrls: new Set(),
  currentBook: null,
  currentShelfPin: null,
  readerKind: null,
  toc: [],
  chapterIndex: 0,
  currentChapter: null,
  pdfPage: 1,
  pdfPageCount: 0,
  pdfObjectUrl: null,
  chapterBusy: false,
  scrollFrame: 0,
  saveTimer: 0,
  autoAdvanceTimer: 0,
  autoAdvanceReadyTimer: 0,
  autoAdvanceReady: false,
  autoAdvanceArmed: false,
  chapterLoadedAt: 0,
  lastScrollY: 0,
  toastTimer: 0,
  lastSavedLocator: "",
  deviceId: getDeviceId(),
  preferences: loadPreferences(),
};

const elements = {
  loginView: document.querySelector("#login-view"),
  libraryView: document.querySelector("#library-view"),
  readerView: document.querySelector("#reader-view"),
  loginForm: document.querySelector("#login-form"),
  loginSubmit: document.querySelector("#login-submit"),
  loginError: document.querySelector("#login-error"),
  password: document.querySelector("#reader-password"),
  passwordToggle: document.querySelector("#password-toggle"),
  logout: document.querySelector("#logout-button"),
  shelfTabs: document.querySelector("#shelf-tabs"),
  shelfPosition: document.querySelector("#shelf-position"),
  shelfTitle: document.querySelector("#shelf-title"),
  shelfMeta: document.querySelector("#shelf-meta"),
  librarySummary: document.querySelector("#library-summary"),
  libraryContent: document.querySelector("#library-content"),
  bookSearch: document.querySelector("#book-search"),
  backToLibrary: document.querySelector("#back-to-library"),
  readerBookName: document.querySelector("#reader-book-name"),
  readerBookAuthor: document.querySelector("#reader-book-author"),
  readerArticle: document.querySelector("#reader-article"),
  readingProgressBar: document.querySelector("#reading-progress-bar"),
  fontSizeValue: document.querySelector("#font-size-value"),
  drawerBackdrop: document.querySelector("#drawer-backdrop"),
  tocDrawer: document.querySelector("#toc-drawer"),
  tocClose: document.querySelector("#toc-close"),
  tocSearch: document.querySelector("#toc-search-input"),
  tocCount: document.querySelector("#toc-count"),
  tocList: document.querySelector("#toc-list"),
  toast: document.querySelector("#toast"),
  liveRegion: document.querySelector("#live-region"),
};

function safeStorage(storage, method, key, value) {
  try {
    return value === undefined ? storage[method](key) : storage[method](key, value);
  } catch (_error) {
    return null;
  }
}

function safeJson(value, fallback = null) {
  if (!value) return fallback;
  try {
    return JSON.parse(value);
  } catch (_error) {
    return fallback;
  }
}

function loadPreferences() {
  const saved = safeJson(safeStorage(localStorage, "getItem", PREFERENCES_KEY), {});
  return {
    theme: THEMES.has(saved?.theme) ? saved.theme : "paper",
    font: FONTS.has(saved?.font) ? saved.font : "song",
    fontSize: Number.isFinite(saved?.fontSize)
      ? Math.min(30, Math.max(16, Number(saved.fontSize)))
      : 20,
  };
}

function savePreferences() {
  safeStorage(localStorage, "setItem", PREFERENCES_KEY, JSON.stringify(state.preferences));
}

function getDeviceId() {
  const stored = safeStorage(localStorage, "getItem", DEVICE_KEY);
  if (stored && /^[A-Za-z0-9._:-]+$/.test(stored)) return stored;
  const random = globalThis.crypto?.randomUUID?.().replaceAll("-", "")
    || `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  const deviceId = `web-${random}`;
  safeStorage(localStorage, "setItem", DEVICE_KEY, deviceId);
  return deviceId;
}

function readSession() {
  const saved = safeJson(safeStorage(sessionStorage, "getItem", SESSION_KEY));
  return saved?.access_token || null;
}

function storeSession(payload) {
  state.token = payload.access_token;
  safeStorage(sessionStorage, "setItem", SESSION_KEY, JSON.stringify(payload));
}

function clearSession() {
  state.token = null;
  safeStorage(sessionStorage, "removeItem", SESSION_KEY);
}

function applyPreferences() {
  const { theme, font, fontSize } = state.preferences;
  document.documentElement.dataset.theme = theme;
  document.documentElement.dataset.readerFont = font;
  document.documentElement.style.setProperty("--reader-size", `${fontSize}px`);
  document.querySelector('meta[name="theme-color"]')?.setAttribute(
    "content",
    theme === "dark" ? "#151a17" : theme === "sepia" ? "#dfe7d1" : "#f3efe4",
  );
  document.querySelectorAll(".theme-option").forEach((button) => {
    const active = button.dataset.themeChoice === theme;
    button.classList.toggle("active", active);
    button.setAttribute("aria-pressed", String(active));
  });
  document.querySelectorAll(".font-select").forEach((select) => {
    select.value = font;
  });
  elements.fontSizeValue.textContent = String(fontSize);
}

function setTheme(theme) {
  if (!THEMES.has(theme)) return;
  state.preferences.theme = theme;
  savePreferences();
  applyPreferences();
}

function setFont(font) {
  if (!FONTS.has(font)) return;
  const position = state.readerKind === "text" ? chapterProgress() : 0;
  state.preferences.font = font;
  savePreferences();
  applyPreferences();
  if (state.readerKind === "text") restoreChapterPosition(position, false);
}

function changeFontSize(delta) {
  const position = state.readerKind === "text" ? chapterProgress() : 0;
  state.preferences.fontSize = Math.min(30, Math.max(16, state.preferences.fontSize + delta));
  savePreferences();
  applyPreferences();
  if (state.readerKind === "text") restoreChapterPosition(position, false);
}

function showView(name) {
  elements.loginView.hidden = name !== "login";
  elements.libraryView.hidden = name !== "library";
  elements.readerView.hidden = name !== "reader";
  if (name !== "reader") closeToc();
}

function showLogin(message = "") {
  closeToc();
  cleanupReader();
  revokeCoverUrls();
  showView("login");
  elements.loginError.textContent = message;
  elements.password.value = "";
  elements.loginSubmit.disabled = false;
  document.title = "页架 · 网页阅读";
  requestAnimationFrame(() => elements.password.focus());
}

function showToast(message, error = false) {
  clearTimeout(state.toastTimer);
  elements.toast.textContent = message;
  elements.toast.classList.toggle("error", error);
  elements.toast.classList.add("show");
  state.toastTimer = window.setTimeout(() => elements.toast.classList.remove("show"), 2600);
}

function announce(message) {
  elements.liveRegion.textContent = "";
  requestAnimationFrame(() => {
    elements.liveRegion.textContent = message;
  });
}

function requestHeaders(extra = {}, shelfPin = null) {
  const headers = new Headers(extra);
  if (state.token) headers.set("Authorization", `Bearer ${state.token}`);
  if (shelfPin) headers.set("X-Shelf-Pin", shelfPin);
  return headers;
}

async function authorizedFetch(path, options = {}) {
  const {
    shelfPin = null,
    skipAuth = false,
    headers: providedHeaders = {},
    ...fetchOptions
  } = options;
  const headers = new Headers(providedHeaders);
  if (!skipAuth && state.token) headers.set("Authorization", `Bearer ${state.token}`);
  if (shelfPin) headers.set("X-Shelf-Pin", shelfPin);
  return fetch(`${API_ROOT}${path}`, { ...fetchOptions, headers });
}

async function readError(response) {
  const contentType = response.headers.get("content-type") || "";
  let payload = null;
  try {
    payload = contentType.includes("application/json") ? await response.json() : await response.text();
  } catch (_error) {
    payload = null;
  }
  const detail = typeof payload === "object" && payload?.detail
    ? payload.detail
    : typeof payload === "string" && payload
      ? payload
      : `请求失败（${response.status}）`;
  return { detail, payload };
}

function isExpiredSession(response) {
  return response.status === 401
    && (response.headers.get("www-authenticate") || "").toLowerCase().includes("bearer");
}

async function api(path, options = {}) {
  const {
    json,
    skipAuth = false,
    shelfPin = null,
    headers: providedHeaders = {},
    ...fetchOptions
  } = options;
  const headers = new Headers(providedHeaders);
  if (json !== undefined) headers.set("Content-Type", "application/json");
  const response = await authorizedFetch(path, {
    ...fetchOptions,
    skipAuth,
    shelfPin,
    headers,
    body: json === undefined ? fetchOptions.body : JSON.stringify(json),
  });
  if (!response.ok) {
    const { detail, payload } = await readError(response);
    if (!skipAuth && isExpiredSession(response)) {
      clearSession();
      showLogin("登录已失效，请重新输入访问密码。");
    }
    throw new ApiError(detail, response.status, payload);
  }
  if (response.status === 204) return null;
  const contentType = response.headers.get("content-type") || "";
  return contentType.includes("application/json") ? response.json() : response.text();
}

async function optionalApi(path, options = {}) {
  try {
    return await api(path, options);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) return null;
    throw error;
  }
}

function revokeCoverUrls() {
  state.coverUrls.forEach((url) => URL.revokeObjectURL(url));
  state.coverUrls.clear();
}

function cleanupReader() {
  clearTimeout(state.saveTimer);
  clearAutoAdvance();
  state.currentBook = null;
  state.currentChapter = null;
  state.readerKind = null;
  state.toc = [];
  state.lastSavedLocator = "";
  if (state.pdfObjectUrl) URL.revokeObjectURL(state.pdfObjectUrl);
  state.pdfObjectUrl = null;
  state.pdfPage = 1;
  state.pdfPageCount = 0;
}

async function boot() {
  applyPreferences();
  state.token = readSession();
  bindEvents();
  if (!state.token) {
    showLogin();
    return;
  }
  try {
    await api("/auth/session");
    await loadLibrary();
  } catch (error) {
    if (state.token) {
      showLogin(error instanceof ApiError ? error.message : "暂时无法连接服务器，请稍后重试。");
    }
  }
}

function bindEvents() {
  elements.loginForm.addEventListener("submit", login);
  elements.passwordToggle.addEventListener("click", togglePassword);
  elements.logout.addEventListener("click", logout);
  elements.bookSearch.addEventListener("input", renderActiveShelf);
  elements.backToLibrary.addEventListener("click", backToLibrary);
  elements.drawerBackdrop.addEventListener("click", closeToc);
  elements.tocClose.addEventListener("click", closeToc);
  elements.tocSearch.addEventListener("input", renderToc);

  document.querySelectorAll(".theme-option").forEach((button) => {
    button.addEventListener("click", () => setTheme(button.dataset.themeChoice));
  });
  document.querySelectorAll(".font-select").forEach((select) => {
    select.addEventListener("change", () => setFont(select.value));
  });
  document.querySelectorAll("[data-font-size]").forEach((button) => {
    button.addEventListener("click", () => changeFontSize(Number(button.dataset.fontSize)));
  });
  document.querySelectorAll(".toc-control").forEach((button) => button.addEventListener("click", openToc));
  document.querySelectorAll(".previous-control").forEach((button) => button.addEventListener("click", previousSection));
  document.querySelectorAll(".next-control").forEach((button) => button.addEventListener("click", nextSection));

  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && elements.tocDrawer.classList.contains("open")) closeToc();
  });
  window.addEventListener("scroll", onReaderScroll, { passive: true });
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden") void saveProgress(true);
  });
  window.addEventListener("pagehide", () => void saveProgress(true));
}

async function login(event) {
  event.preventDefault();
  const password = elements.password.value;
  if (!password) return;
  elements.loginSubmit.disabled = true;
  elements.loginError.textContent = "";
  elements.loginSubmit.firstElementChild.textContent = "正在验证";
  try {
    const session = await api("/auth/login", {
      method: "POST",
      skipAuth: true,
      json: { password },
    });
    storeSession(session);
    elements.password.value = "";
    await loadLibrary();
  } catch (error) {
    elements.loginError.textContent = error instanceof ApiError
      ? error.message
      : "无法连接服务器，请检查网络后重试。";
  } finally {
    elements.loginSubmit.disabled = false;
    elements.loginSubmit.firstElementChild.textContent = "进入书架";
  }
}

function togglePassword() {
  const showing = elements.password.type === "text";
  elements.password.type = showing ? "password" : "text";
  elements.passwordToggle.textContent = showing ? "显示" : "隐藏";
  elements.passwordToggle.setAttribute("aria-label", showing ? "显示密码" : "隐藏密码");
  elements.passwordToggle.setAttribute("aria-pressed", String(!showing));
  elements.password.focus();
}

function logout() {
  clearSession();
  state.shelves = [];
  state.unlockedShelves.clear();
  state.shelfPins.clear();
  showLogin("已安全退出当前阅读会话。");
}

async function loadLibrary() {
  showView("library");
  elements.libraryContent.replaceChildren(createLoadingState("正在取书……"));
  document.title = "我的书架 · 页架";
  try {
    const shelves = await api("/shelves");
    state.shelves = shelves.map((shelf) => state.unlockedShelves.get(shelf.id) || shelf);
    const lastShelf = safeStorage(localStorage, "getItem", LAST_SHELF_KEY);
    state.activeShelfId = state.shelves.some((shelf) => shelf.id === lastShelf)
      ? lastShelf
      : state.shelves[0]?.id || null;
    renderLibrary();
  } catch (error) {
    if (!state.token) return;
    renderLibraryError(error instanceof ApiError ? error.message : "无法连接服务器。");
  }
}

function renderLibrary() {
  const bookCount = state.shelves.reduce((sum, shelf) => sum + shelf.book_count, 0);
  elements.librarySummary.textContent = state.shelves.length
    ? `${state.shelves.length} 个书架 · ${bookCount} 本书`
    : "还没有可阅读的书架";
  renderShelfTabs();
  renderActiveShelf();
}

function renderShelfTabs() {
  elements.shelfTabs.replaceChildren();
  state.shelves.forEach((shelf, index) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "shelf-tab";
    button.classList.toggle("active", shelf.id === state.activeShelfId);
    button.setAttribute("aria-current", shelf.id === state.activeShelfId ? "page" : "false");
    button.dataset.shelfId = shelf.id;
    const name = document.createElement("strong");
    name.textContent = shelf.name;
    const meta = document.createElement("span");
    meta.textContent = shelf.locked ? `已加密 · ${shelf.book_count} 本` : `${shelf.book_count} 本`;
    button.append(name, meta);
    button.addEventListener("click", () => {
      state.activeShelfId = shelf.id;
      safeStorage(localStorage, "setItem", LAST_SHELF_KEY, shelf.id);
      renderShelfTabs();
      renderActiveShelf();
      button.scrollIntoView({ behavior: "smooth", inline: "center", block: "nearest" });
      announce(`已切换到${shelf.name}`);
    });
    elements.shelfTabs.append(button);
    if (index === 0 && !state.activeShelfId) state.activeShelfId = shelf.id;
  });
}

function activeShelf() {
  return state.shelves.find((shelf) => shelf.id === state.activeShelfId) || null;
}

function renderActiveShelf() {
  const shelf = activeShelf();
  revokeCoverUrls();
  if (!shelf) {
    elements.shelfPosition.textContent = "00 / 00";
    elements.shelfTitle.textContent = "暂无书架";
    elements.shelfMeta.textContent = "";
    elements.libraryContent.replaceChildren(createEmptyState("空", "书架还是空的", "请先在管理后台添加书架并扫描书籍。"));
    return;
  }
  const shelfIndex = state.shelves.findIndex((candidate) => candidate.id === shelf.id);
  elements.shelfPosition.textContent = `${String(shelfIndex + 1).padStart(2, "0")} / ${String(state.shelves.length).padStart(2, "0")}`;
  elements.shelfTitle.textContent = shelf.name;
  elements.shelfMeta.textContent = `${shelf.book_count} 本 · ${formatBytes(shelf.total_bytes)}`;

  if (shelf.locked) {
    elements.libraryContent.replaceChildren(createLockedShelf(shelf));
    return;
  }

  const query = elements.bookSearch.value.trim().toLocaleLowerCase("zh-CN");
  const books = shelf.books.filter((book) => {
    if (!query) return true;
    return book.title.toLocaleLowerCase("zh-CN").includes(query)
      || (book.author || "").toLocaleLowerCase("zh-CN").includes(query);
  });
  if (!books.length) {
    const message = query ? "没有找到匹配的书" : "这个书架还没有书";
    const hint = query ? "换一个关键词试试。" : "在管理后台扫描书架后，新书会出现在这里。";
    elements.libraryContent.replaceChildren(createEmptyState("空", message, hint));
    return;
  }

  const grid = document.createElement("div");
  grid.className = "book-grid";
  books.forEach((book) => grid.append(createBookCard(book, shelf)));
  elements.libraryContent.replaceChildren(grid);
}

function createLockedShelf(shelf) {
  const section = document.createElement("section");
  section.className = "locked-shelf";
  const mark = document.createElement("span");
  mark.className = "state-mark";
  mark.setAttribute("aria-hidden", "true");
  mark.textContent = "锁";
  const title = document.createElement("h3");
  title.textContent = "这个书架已加密";
  const description = document.createElement("p");
  description.textContent = `${shelf.name}收录 ${shelf.book_count} 本书，请输入四位书架密码。`;
  const form = document.createElement("form");
  form.className = "unlock-form";
  const input = document.createElement("input");
  input.type = "password";
  input.inputMode = "numeric";
  input.pattern = "[0-9]{4}";
  input.maxLength = 4;
  input.placeholder = "••••";
  input.autocomplete = "off";
  input.setAttribute("aria-label", "四位书架密码");
  const submit = document.createElement("button");
  submit.type = "submit";
  submit.textContent = "解锁 →";
  const error = document.createElement("p");
  error.className = "unlock-error";
  error.setAttribute("role", "alert");
  form.append(input, submit);
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const pin = input.value.replace(/\D/g, "").slice(0, 4);
    if (pin.length !== 4) {
      error.textContent = "请输入四位数字密码。";
      return;
    }
    submit.disabled = true;
    submit.textContent = "验证中";
    error.textContent = "";
    try {
      const unlocked = await api(`/shelves/${encodeURIComponent(shelf.id)}/unlock`, {
        method: "POST",
        json: { pin },
      });
      state.unlockedShelves.set(shelf.id, unlocked);
      state.shelfPins.set(shelf.id, pin);
      state.shelves = state.shelves.map((item) => item.id === shelf.id ? unlocked : item);
      renderShelfTabs();
      renderActiveShelf();
      announce(`${shelf.name}已解锁`);
    } catch (unlockError) {
      error.textContent = unlockError instanceof ApiError ? unlockError.message : "暂时无法验证密码。";
    } finally {
      submit.disabled = false;
      submit.textContent = "解锁 →";
    }
  });
  input.addEventListener("input", () => {
    input.value = input.value.replace(/\D/g, "").slice(0, 4);
    error.textContent = "";
  });
  section.append(mark, title, description, form, error);
  requestAnimationFrame(() => input.focus());
  return section;
}

function createBookCard(book, shelf) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = "book-card";
  button.disabled = !book.can_open;
  const disabledReason = book.password_required
    ? "文件自身带密码，暂时无法打开"
    : book.parse_status !== "ready"
      ? "书籍尚未解析完成"
      : "";
  button.setAttribute("aria-label", `${book.title}${disabledReason ? `，${disabledReason}` : "，打开阅读"}`);

  const cover = document.createElement("span");
  cover.className = "book-cover";
  const fallback = document.createElement("span");
  fallback.className = "book-cover-fallback";
  const fallbackTitle = document.createElement("strong");
  fallbackTitle.textContent = book.title;
  const fallbackFormat = document.createElement("span");
  fallbackFormat.textContent = `PAGE SHELF · ${book.format.toUpperCase()}`;
  fallback.append(fallbackTitle, fallbackFormat);
  cover.append(fallback);

  const info = document.createElement("span");
  info.className = "book-info";
  const format = document.createElement("span");
  format.className = "book-format";
  format.textContent = book.format.toUpperCase();
  const title = document.createElement("h3");
  title.textContent = book.title;
  const author = document.createElement("p");
  author.textContent = book.author || "未知作者";
  const stat = document.createElement("span");
  stat.className = "book-stat";
  const length = document.createElement("span");
  length.textContent = book.format === PDF_FORMAT
    ? `${book.page_count || 0} 页`
    : `${book.chapter_count || 0} 章`;
  const size = document.createElement("span");
  size.textContent = disabledReason || formatBytes(book.file_size);
  stat.append(length, size);
  info.append(format, title, author, stat);
  button.append(cover, info);
  button.addEventListener("click", () => void openBook(book, shelf));
  if (book.cover_status === "ready") void loadBookCover(book, shelf, cover);
  return button;
}

async function loadBookCover(book, shelf, container) {
  try {
    const shelfPin = state.shelfPins.get(shelf.id) || null;
    const response = await authorizedFetch(`/books/${encodeURIComponent(book.id)}/cover`, { shelfPin });
    if (!response.ok) return;
    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    if (!container.isConnected) {
      URL.revokeObjectURL(url);
      return;
    }
    state.coverUrls.add(url);
    const image = document.createElement("img");
    image.src = url;
    image.alt = "";
    image.loading = "lazy";
    image.decoding = "async";
    container.append(image);
  } catch (_error) {
    // A generated title cover remains visible when the optional cover request fails.
  }
}

function createLoadingState(message) {
  const loading = document.createElement("div");
  loading.className = "loading-state";
  loading.setAttribute("role", "status");
  loading.append(document.createElement("span"), document.createElement("span"), document.createElement("span"));
  const text = document.createElement("p");
  text.textContent = message;
  loading.append(text);
  return loading;
}

function createEmptyState(markText, titleText, detailText, action = null) {
  const section = document.createElement("section");
  section.className = "empty-state";
  const mark = document.createElement("span");
  mark.className = "state-mark";
  mark.setAttribute("aria-hidden", "true");
  mark.textContent = markText;
  const title = document.createElement("h3");
  title.textContent = titleText;
  const detail = document.createElement("p");
  detail.textContent = detailText;
  section.append(mark, title, detail);
  if (action) section.append(action);
  return section;
}

function renderLibraryError(message) {
  elements.shelfTabs.replaceChildren();
  elements.shelfTitle.textContent = "书架暂时无法加载";
  elements.shelfMeta.textContent = "";
  const retry = document.createElement("button");
  retry.type = "button";
  retry.className = "primary-button";
  retry.textContent = "重新加载";
  retry.addEventListener("click", () => void loadLibrary());
  const stateNode = createEmptyState("!", "没有取到书架", message, retry);
  stateNode.className = "error-state";
  elements.libraryContent.replaceChildren(stateNode);
}

async function openBook(book, shelf) {
  revokeCoverUrls();
  state.currentBook = book;
  state.currentShelfPin = state.shelfPins.get(shelf.id) || null;
  state.readerKind = book.format === PDF_FORMAT ? "pdf" : "text";
  state.currentChapter = null;
  state.toc = [];
  state.lastSavedLocator = "";
  elements.readerBookName.textContent = book.title;
  elements.readerBookAuthor.textContent = book.author || "";
  elements.tocSearch.value = "";
  showView("reader");
  setReaderLoading(book.format === PDF_FORMAT ? "正在载入原版页面……" : "正在展开书页……");
  document.title = `${book.title} · 页架`;
  window.scrollTo({ top: 0, behavior: "auto" });
  updateNavigation();
  try {
    if (book.format === PDF_FORMAT) {
      await openPdfBook(book);
    } else {
      await openTextBook(book);
    }
  } catch (error) {
    if (!state.token) return;
    renderReaderError(error instanceof ApiError ? error.message : "书籍暂时无法打开。");
  }
}

function setReaderLoading(message) {
  elements.readerArticle.setAttribute("aria-busy", "true");
  const wrapper = document.createElement("div");
  wrapper.className = "chapter-loading";
  const mark = document.createElement("span");
  mark.className = "loading-mark";
  mark.setAttribute("aria-hidden", "true");
  mark.textContent = "页";
  const text = document.createElement("p");
  text.textContent = message;
  wrapper.append(mark, text);
  elements.readerArticle.replaceChildren(wrapper);
}

async function openTextBook(book) {
  const bookId = encodeURIComponent(book.id);
  const [toc, progress] = await Promise.all([
    api(`/books/${bookId}/toc`, { shelfPin: state.currentShelfPin }),
    optionalApi(`/books/${bookId}/progress`, { shelfPin: state.currentShelfPin }),
  ]);
  if (!toc.chapter_supported || !toc.items.length) {
    throw new ApiError("这本书没有可供网页阅读的章节。", 422);
  }
  state.toc = toc.items;
  state.readerKind = "text";
  const restored = restoredChapter(progress);
  renderToc();
  await loadTextChapter(restored.index, { restoreRatio: restored.ratio, skipSave: true });
}

function restoredChapter(progress) {
  if (!progress?.locator_json || !state.toc.length) return { index: 0, ratio: 0 };
  const locator = progress.locator_json;
  let index = state.toc.findIndex((item) => item.id === locator.chapter_id);
  if (index < 0 && Number.isInteger(locator.chapter_index)) {
    index = Math.min(state.toc.length - 1, Math.max(0, locator.chapter_index));
  }
  if (index < 0) index = Math.min(state.toc.length - 1, Math.floor((progress.progression || 0) * state.toc.length));
  let ratio = Number(locator.chapter_progress);
  if (!Number.isFinite(ratio) && Number.isFinite(progress.progression)) {
    ratio = progress.progression * state.toc.length - index;
  }
  return { index, ratio: clamp(Number.isFinite(ratio) ? ratio : 0, 0, 1) };
}

async function loadTextChapter(index, { restoreRatio = 0, skipSave = false, autoAdvance = false } = {}) {
  if (state.chapterBusy || index < 0 || index >= state.toc.length) return;
  state.chapterBusy = true;
  clearAutoAdvance();
  if (!skipSave && state.currentChapter) await saveProgress(true);
  setReaderLoading("正在展开书页……");
  state.chapterIndex = index;
  updateNavigation();
  try {
    const tocItem = state.toc[index];
    const chapter = await api(
      `/books/${encodeURIComponent(state.currentBook.id)}/chapters/${encodeURIComponent(tocItem.id)}`,
      { shelfPin: state.currentShelfPin },
    );
    state.currentChapter = chapter;
    renderChapter(chapter, index);
    renderToc();
    state.chapterLoadedAt = Date.now();
    state.lastScrollY = window.scrollY;
    restoreChapterPosition(restoreRatio, true);
    announce(`已打开${chapter.title}`);
    if (autoAdvance) showToast("已自动进入下一章");
  } catch (error) {
    if (state.token) {
      renderReaderError(error instanceof ApiError ? error.message : "章节暂时无法打开。");
    }
  } finally {
    state.chapterBusy = false;
    updateNavigation();
  }
}

function renderChapter(chapter, index) {
  const heading = document.createElement("header");
  heading.className = "chapter-heading";
  const number = document.createElement("p");
  number.className = "chapter-number";
  number.textContent = `CHAPTER ${String(index + 1).padStart(2, "0")} / ${String(state.toc.length).padStart(2, "0")}`;
  const title = document.createElement("h1");
  title.id = "current-chapter-title";
  title.tabIndex = -1;
  title.textContent = chapter.title;
  heading.append(number, title);

  const content = document.createElement("div");
  content.className = "chapter-content";
  const normalized = (chapter.body || "").replace(/\r\n?/g, "\n").trim();
  let blocks = normalized.split(/\n+/).map((block) => block.trim()).filter(Boolean);
  if (blocks[0] && normalizeTitle(blocks[0]) === normalizeTitle(chapter.title)) blocks = blocks.slice(1);
  if (!blocks.length) {
    const empty = document.createElement("p");
    empty.className = "empty-chapter";
    empty.textContent = "本章没有正文内容。";
    content.append(empty);
  } else {
    const fragment = document.createDocumentFragment();
    blocks.forEach((block) => {
      const paragraph = document.createElement("p");
      paragraph.textContent = block;
      fragment.append(paragraph);
    });
    content.append(fragment);
  }
  elements.readerArticle.replaceChildren(heading, content);
  elements.readerArticle.removeAttribute("aria-busy");
}

function normalizeTitle(value) {
  return value.replace(/\s+/g, "").replace(/[：:—–-]+$/g, "").toLocaleLowerCase("zh-CN");
}

function restoreChapterPosition(ratio, startAtTop) {
  requestAnimationFrame(() => requestAnimationFrame(() => {
    const content = elements.readerArticle.querySelector(".chapter-content");
    if (!content) return;
    const contentTop = content.getBoundingClientRect().top + window.scrollY;
    const travel = Math.max(0, content.offsetHeight - window.innerHeight * .72);
    const top = ratio > 0 ? contentTop + travel * clamp(ratio, 0, 1) : startAtTop ? 0 : window.scrollY;
    window.scrollTo({ top, behavior: "auto" });
    updateReadingProgress();
    if (startAtTop && ratio === 0) {
      elements.readerArticle.querySelector("h1")?.focus({ preventScroll: true });
    }
    clearTimeout(state.autoAdvanceReadyTimer);
    state.autoAdvanceReadyTimer = window.setTimeout(() => {
      state.autoAdvanceReadyTimer = 0;
      state.lastScrollY = window.scrollY;
      state.autoAdvanceReady = true;
    }, 500);
  }));
}

function chapterProgress() {
  const content = elements.readerArticle.querySelector(".chapter-content");
  if (!content) return 0;
  const contentTop = content.getBoundingClientRect().top + window.scrollY;
  const travel = Math.max(1, content.offsetHeight - window.innerHeight * .72);
  return clamp((window.scrollY - contentTop) / travel, 0, 1);
}

async function openPdfBook(book) {
  const bookId = encodeURIComponent(book.id);
  const [navigation, progress, response] = await Promise.all([
    api(`/books/${bookId}/pdf-navigation`, { shelfPin: state.currentShelfPin }),
    optionalApi(`/books/${bookId}/progress`, { shelfPin: state.currentShelfPin }),
    authorizedFetch(`/books/${bookId}/file`, { shelfPin: state.currentShelfPin }),
  ]);
  if (!response.ok) {
    const { detail, payload } = await readError(response);
    throw new ApiError(detail, response.status, payload);
  }
  const blob = await response.blob();
  state.pdfObjectUrl = URL.createObjectURL(blob);
  state.pdfPageCount = navigation.page_count || book.page_count || 1;
  state.pdfPage = clamp((progress?.page_index ?? 0) + 1, 1, state.pdfPageCount);
  state.toc = buildPdfToc(navigation.items, state.pdfPageCount);
  state.readerKind = "pdf";
  renderPdf();
  renderToc();
  updateNavigation();
  updateReadingProgress();
  announce(`已打开${book.title}，第${state.pdfPage}页`);
}

function flattenPdfItems(items, depth = 0, result = []) {
  items.forEach((item) => {
    result.push({ title: item.title, page: item.page, depth });
    if (item.children?.length) flattenPdfItems(item.children, depth + 1, result);
  });
  return result;
}

function buildPdfToc(items, pageCount) {
  const navigationItems = flattenPdfItems(items || []);
  if (navigationItems.length) return navigationItems;
  const step = pageCount > 500 ? 10 : 1;
  const pages = [];
  for (let page = 1; page <= pageCount; page += step) {
    pages.push({ title: `第 ${page} 页`, page, depth: 0 });
  }
  if (pages.at(-1)?.page !== pageCount) pages.push({ title: `第 ${pageCount} 页`, page: pageCount, depth: 0 });
  return pages;
}

function renderPdf() {
  const wrapper = document.createElement("div");
  wrapper.className = "pdf-reader";
  const note = document.createElement("p");
  note.className = "pdf-note";
  note.textContent = `原版纵向阅读 · 第 ${state.pdfPage} / ${state.pdfPageCount} 页`;
  const frame = document.createElement("iframe");
  frame.className = "pdf-frame";
  frame.title = `${state.currentBook.title} PDF 阅读区`;
  frame.src = pdfFrameUrl();
  wrapper.append(note, frame);
  elements.readerArticle.replaceChildren(wrapper);
  elements.readerArticle.removeAttribute("aria-busy");
}

function pdfFrameUrl() {
  return `${state.pdfObjectUrl}#page=${state.pdfPage}&view=FitH&toolbar=0&navpanes=0`;
}

function setPdfPage(page) {
  const nextPage = clamp(page, 1, state.pdfPageCount);
  if (nextPage === state.pdfPage && elements.readerArticle.querySelector(".pdf-frame")) return;
  state.pdfPage = nextPage;
  const frame = elements.readerArticle.querySelector(".pdf-frame");
  const note = elements.readerArticle.querySelector(".pdf-note");
  if (frame) frame.src = pdfFrameUrl();
  if (note) note.textContent = `原版纵向阅读 · 第 ${state.pdfPage} / ${state.pdfPageCount} 页`;
  updateNavigation();
  renderToc();
  updateReadingProgress();
  void saveProgress(true);
  window.scrollTo({ top: 0, behavior: "smooth" });
  announce(`第${state.pdfPage}页`);
}

function previousSection() {
  if (state.chapterBusy) return;
  if (state.readerKind === "pdf") setPdfPage(state.pdfPage - 1);
  else if (state.readerKind === "text") void loadTextChapter(state.chapterIndex - 1);
}

function nextSection() {
  if (state.chapterBusy) return;
  if (state.readerKind === "pdf") setPdfPage(state.pdfPage + 1);
  else if (state.readerKind === "text") void loadTextChapter(state.chapterIndex + 1);
}

function updateNavigation() {
  const isPdf = state.readerKind === "pdf";
  const previousDisabled = isPdf
    ? state.pdfPage <= 1
    : state.chapterBusy || state.chapterIndex <= 0 || !state.toc.length;
  const nextDisabled = isPdf
    ? state.pdfPage >= state.pdfPageCount
    : state.chapterBusy || state.chapterIndex >= state.toc.length - 1 || !state.toc.length;
  document.querySelectorAll(".previous-control").forEach((button) => {
    button.disabled = previousDisabled;
    button.querySelector("span:last-child").textContent = isPdf ? "上一页" : "上一章";
  });
  document.querySelectorAll(".next-control").forEach((button) => {
    button.disabled = nextDisabled;
    button.querySelector("span:first-child").textContent = isPdf ? "下一页" : "下一章";
  });
}

function openToc() {
  if (!state.currentBook) return;
  renderToc();
  elements.tocDrawer.classList.add("open");
  elements.tocDrawer.setAttribute("aria-hidden", "false");
  document.body.classList.add("drawer-open");
  elements.tocClose.focus();
  const active = elements.tocList.querySelector(".toc-item.active");
  active?.scrollIntoView({ block: "center" });
}

function closeToc() {
  const wasOpen = elements.tocDrawer.classList.contains("open");
  elements.tocDrawer.classList.remove("open");
  elements.tocDrawer.setAttribute("aria-hidden", "true");
  document.body.classList.remove("drawer-open");
  if (wasOpen) document.querySelector("#reader-toolbar .toc-control")?.focus();
}

function renderToc() {
  const query = elements.tocSearch.value.trim().toLocaleLowerCase("zh-CN");
  elements.tocList.replaceChildren();
  const entries = state.toc
    .map((item, index) => ({ item, index }))
    .filter(({ item }) => !query || item.title.toLocaleLowerCase("zh-CN").includes(query));
  elements.tocCount.textContent = state.readerKind === "pdf"
    ? `${state.pdfPageCount} 页 · ${entries.length} 个目录节点`
    : `${entries.length} / ${state.toc.length} 章`;

  if (!entries.length) {
    const empty = document.createElement("p");
    empty.className = "toc-empty";
    empty.textContent = "没有找到匹配的目录项。";
    elements.tocList.append(empty);
    return;
  }

  const fragment = document.createDocumentFragment();
  entries.forEach(({ item, index }) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "toc-item";
    const current = state.readerKind === "pdf"
      ? item.page === state.pdfPage
      : index === state.chapterIndex;
    button.classList.toggle("active", current);
    if (current) button.setAttribute("aria-current", "page");
    if (item.depth) button.style.paddingLeft = `${Math.min(item.depth, 4) * 1.1 + .3}rem`;
    const number = document.createElement("span");
    number.textContent = state.readerKind === "pdf"
      ? String(item.page).padStart(3, "0")
      : String(index + 1).padStart(3, "0");
    const title = document.createElement("span");
    title.textContent = item.title;
    button.append(number, title);
    button.addEventListener("click", () => {
      closeToc();
      if (state.readerKind === "pdf") setPdfPage(item.page);
      else void loadTextChapter(index);
    });
    fragment.append(button);
  });
  elements.tocList.append(fragment);
}

function onReaderScroll() {
  if (elements.readerView.hidden || !state.currentBook) return;
  const currentScrollY = window.scrollY;
  const scrollDelta = currentScrollY - state.lastScrollY;
  state.lastScrollY = currentScrollY;
  if (!state.scrollFrame) {
    state.scrollFrame = requestAnimationFrame(() => {
      state.scrollFrame = 0;
      updateReadingProgress();
    });
  }
  if (state.readerKind === "text" && state.currentChapter) {
    clearTimeout(state.saveTimer);
    state.saveTimer = window.setTimeout(() => void saveProgress(true), 1000);
    scheduleAutoAdvance(currentScrollY, scrollDelta);
  }
}

function clearAutoAdvance() {
  clearTimeout(state.autoAdvanceTimer);
  clearTimeout(state.autoAdvanceReadyTimer);
  state.autoAdvanceTimer = 0;
  state.autoAdvanceReadyTimer = 0;
  state.autoAdvanceReady = false;
  state.autoAdvanceArmed = false;
}

function scheduleAutoAdvance(currentScrollY, scrollDelta) {
  if (
    state.readerKind !== "text"
    || state.chapterBusy
    || !state.currentChapter
    || state.chapterIndex >= state.toc.length - 1
  ) {
    clearAutoAdvance();
    return;
  }
  if (!state.autoAdvanceReady) return;

  const scrollingDown = scrollDelta > 1;
  const scrollingUp = scrollDelta < -1;
  if (scrollingDown && Date.now() - state.chapterLoadedAt >= 500) {
    state.autoAdvanceArmed = true;
  }

  const remaining = Math.max(
    0,
    document.documentElement.scrollHeight - currentScrollY - window.innerHeight,
  );
  if (!state.autoAdvanceArmed || scrollingUp || remaining > 32) {
    if (scrollingUp || remaining > 120) {
      clearTimeout(state.autoAdvanceTimer);
      state.autoAdvanceTimer = 0;
    }
    return;
  }
  if (!scrollingDown && !state.autoAdvanceTimer) return;
  if (state.autoAdvanceTimer) return;

  state.autoAdvanceTimer = window.setTimeout(() => {
    state.autoAdvanceTimer = 0;
    const latestRemaining = Math.max(
      0,
      document.documentElement.scrollHeight - window.scrollY - window.innerHeight,
    );
    if (
      !state.autoAdvanceArmed
      || state.chapterBusy
      || state.chapterIndex >= state.toc.length - 1
      || latestRemaining > 64
    ) return;

    state.autoAdvanceArmed = false;
    void loadTextChapter(state.chapterIndex + 1, { autoAdvance: true });
  }, 220);
}

function updateReadingProgress() {
  let progression = 0;
  if (state.readerKind === "pdf" && state.pdfPageCount) {
    progression = state.pdfPage / state.pdfPageCount;
  } else if (state.readerKind === "text" && state.toc.length) {
    progression = (state.chapterIndex + chapterProgress()) / state.toc.length;
  }
  elements.readingProgressBar.style.width = `${clamp(progression, 0, 1) * 100}%`;
}

async function saveProgress(silent = false) {
  if (!state.token || !state.currentBook || !state.readerKind) return;
  let payload;
  if (state.readerKind === "pdf") {
    if (!state.pdfPageCount) return;
    payload = {
      page_index: state.pdfPage - 1,
      page_count: state.pdfPageCount,
      locator_json: {
        type: "pdf",
        page_index: state.pdfPage - 1,
        page: state.pdfPage,
        view: "continuous-web",
      },
    };
  } else {
    if (!state.currentChapter || !state.toc.length) return;
    const ratio = Number(chapterProgress().toFixed(4));
    payload = {
      progression: Number(((state.chapterIndex + ratio) / state.toc.length).toFixed(6)),
      locator_json: {
        type: ["epub", "mobi"].includes(state.currentBook.format) ? state.currentBook.format : "text",
        chapter_id: state.currentChapter.id,
        chapter_index: state.chapterIndex,
        chapter_title: state.currentChapter.title,
        chapter_progress: ratio,
        char_offset: Math.round((state.currentChapter.body?.length || 0) * ratio),
        view: "scroll",
        font_size_px: state.preferences.fontSize,
      },
    };
  }
  const serialized = JSON.stringify(payload);
  if (serialized === state.lastSavedLocator) return;
  try {
    await api(
      `/books/${encodeURIComponent(state.currentBook.id)}/progress/${encodeURIComponent(state.deviceId)}`,
      {
        method: "PUT",
        json: payload,
        shelfPin: state.currentShelfPin,
        keepalive: true,
      },
    );
    state.lastSavedLocator = serialized;
  } catch (error) {
    if (!silent && state.token) showToast(error instanceof ApiError ? error.message : "阅读进度暂时无法同步。", true);
  }
}

async function backToLibrary() {
  await saveProgress(true);
  cleanupReader();
  showView("library");
  document.title = "我的书架 · 页架";
  renderActiveShelf();
  window.scrollTo({ top: 0, behavior: "auto" });
}

function renderReaderError(message) {
  state.currentChapter = null;
  const back = document.createElement("button");
  back.type = "button";
  back.className = "primary-button";
  back.textContent = "返回书架";
  back.addEventListener("click", () => void backToLibrary());
  const node = createEmptyState("!", "这本书暂时打不开", message, back);
  node.className = "error-state";
  elements.readerArticle.replaceChildren(node);
  elements.readerArticle.removeAttribute("aria-busy");
  state.toc = [];
  updateNavigation();
}

function formatBytes(bytes) {
  if (!Number.isFinite(bytes) || bytes < 0) return "—";
  if (bytes >= 1024 ** 3) return `${(bytes / 1024 ** 3).toFixed(1)} GB`;
  if (bytes >= 1024 ** 2) return `${(bytes / 1024 ** 2).toFixed(1)} MB`;
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${bytes} B`;
}

function clamp(value, minimum, maximum) {
  return Math.min(maximum, Math.max(minimum, value));
}

void boot();

(() => {
  "use strict";

  const root = document.documentElement;
  const phoneScreen = document.querySelector("#phone-screen");
  const canvas = document.querySelector("#glass-canvas");
  const rendererLabel = document.querySelector("#renderer-label");
  const themeToggle = document.querySelector("#theme-toggle");
  const appThemeButton = document.querySelector("#app-theme-button");
  const motionToggle = document.querySelector("#motion-toggle");
  const searchButton = document.querySelector("#search-button");
  const searchWrap = document.querySelector("#search-wrap");
  const searchInput = document.querySelector("#search-input");
  const refreshButton = document.querySelector("#refresh-button");
  const playButton = document.querySelector("#play-button");
  const resetButton = document.querySelector("#reset-button");
  const themeMeta = document.querySelector('meta[name="theme-color"]');
  const glassElements = [...document.querySelectorAll("[data-glass]")];

  let sceneDirty = true;
  let refreshBurst = 0;
  let touch = { x: 0, y: 0, current: 0, target: 0 };
  let previousFrame = performance.now();
  let motionClock = 0;
  let lastSceneUpload = -Infinity;
  let frameHandle = 0;

  const controls = {
    blur: bindRange("blur-control", "blur-value", 2, " px"),
    edge: bindRange("edge-control", "edge-value", 1, " px"),
    bend: bindRange("bend-control", "bend-value", 1, " px"),
    dispersion: bindRange("dispersion-control", "dispersion-value", 2, " px"),
  };

  const defaults = { blur: 2.25, edge: 10, bend: 5.5, dispersion: 0.18 };
  const surfaceMotion = new Map(glassElements.map((element) => [element, { current: 0, target: 0 }]));
  const sourceCanvas = document.createElement("canvas");
  const sourceContext = sourceCanvas.getContext("2d", { alpha: false });
  const backdropImage = new Image();
  backdropImage.decoding = "async";
  backdropImage.fetchPriority = "high";
  backdropImage.src = "./assets/panda-garden.png";
  backdropImage.addEventListener("load", () => {
    sceneDirty = true;
    lastSceneUpload = -Infinity;
  });
  backdropImage.addEventListener("error", () => {
    console.warn("The illustrated backdrop could not be loaded; using the generated fallback scene.");
  });
  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  let motionPaused = reducedMotion;
  let searchOpen = false;

  const renderer = createRenderer(canvas);
  if (!renderer) {
    document.body.classList.add("no-webgl");
    rendererLabel.textContent = "CSS fallback";
  }

  syncMotionButton();
  syncThemeButtons();
  setupInteractions();

  const resizeObserver = new ResizeObserver(() => {
    sceneDirty = true;
    lastSceneUpload = -Infinity;
  });
  resizeObserver.observe(phoneScreen);

  frameHandle = requestAnimationFrame(renderFrame);

  function bindRange(inputId, outputId, fractionDigits, suffix) {
    const input = document.querySelector(`#${inputId}`);
    const output = document.querySelector(`#${outputId}`);
    const binding = { input, output, value: Number(input.value), fractionDigits, suffix };
    const update = () => {
      binding.value = Number(input.value);
      output.value = `${formatNumber(binding.value, fractionDigits)}${suffix}`;
      sceneDirty = true;
    };
    input.addEventListener("input", update);
    update();
    return binding;
  }

  function formatNumber(value, fractionDigits) {
    return value.toFixed(fractionDigits).replace(/\.0+$|(?<=\.[0-9])0+$/g, "");
  }

  function setupInteractions() {
    glassElements.forEach((element) => {
      element.addEventListener("pointerdown", (event) => {
        const state = surfaceMotion.get(element);
        state.target = 1;
        element.classList.add("is-pressed");
        updateTouch(event);
        touch.target = 1;
        if (element.setPointerCapture) element.setPointerCapture(event.pointerId);
      });

      element.addEventListener("pointermove", (event) => {
        if ((event.buttons & 1) === 1) updateTouch(event);
      });

      const release = () => {
        const state = surfaceMotion.get(element);
        state.target = 0;
        element.classList.remove("is-pressed");
        touch.target = 0;
      };
      element.addEventListener("pointerup", release);
      element.addEventListener("pointercancel", release);
      element.addEventListener("lostpointercapture", release);
    });

    window.addEventListener("pointerup", () => {
      touch.target = 0;
      surfaceMotion.forEach((state, element) => {
        state.target = 0;
        element.classList.remove("is-pressed");
      });
    });

    themeToggle.addEventListener("click", toggleTheme);
    appThemeButton.addEventListener("click", toggleTheme);

    motionToggle.addEventListener("click", () => {
      motionPaused = !motionPaused;
      syncMotionButton();
      sceneDirty = true;
    });

    searchButton.addEventListener("click", () => setSearchOpen(!searchOpen));
    searchInput.addEventListener("input", () => { sceneDirty = true; });
    searchInput.addEventListener("keydown", (event) => {
      if (event.key === "Escape") setSearchOpen(false);
    });

    refreshButton.addEventListener("click", () => {
      refreshBurst = 1;
      refreshButton.classList.remove("is-spinning");
      void refreshButton.offsetWidth;
      refreshButton.classList.add("is-spinning");
      window.setTimeout(() => refreshButton.classList.remove("is-spinning"), 650);
      sceneDirty = true;
    });

    playButton.addEventListener("click", () => {
      const playing = playButton.getAttribute("aria-pressed") !== "true";
      playButton.setAttribute("aria-pressed", String(playing));
      playButton.setAttribute("aria-label", playing ? "暂停朗读" : "开始朗读");
      playButton.classList.toggle("is-playing", playing);
      playButton.querySelector("path").setAttribute(
        "d",
        playing ? "M7 5h3.7v14H7zm6.3 0H17v14h-3.7z" : "M8 5v14l11-7z",
      );
    });

    resetButton.addEventListener("click", () => {
      Object.entries(defaults).forEach(([name, value]) => {
        controls[name].input.value = String(value);
        controls[name].input.dispatchEvent(new Event("input", { bubbles: true }));
      });
    });
  }

  function toggleTheme() {
    root.dataset.theme = root.dataset.theme === "dark" ? "light" : "dark";
    syncThemeButtons();
    sceneDirty = true;
    lastSceneUpload = -Infinity;
  }

  function syncThemeButtons() {
    const dark = root.dataset.theme === "dark";
    themeToggle.setAttribute("aria-pressed", String(dark));
    themeToggle.setAttribute("aria-label", dark ? "切换为纸白主题" : "切换为夜间主题");
    themeToggle.querySelector("span").textContent = dark ? "纸白" : "夜间";
    appThemeButton.setAttribute("aria-pressed", String(dark));
    themeMeta.content = dark ? "#05070a" : "#f6f2e9";
  }

  function syncMotionButton() {
    motionToggle.setAttribute("aria-pressed", String(motionPaused));
    motionToggle.setAttribute("aria-label", motionPaused ? "继续背景运动" : "暂停背景运动");
    motionToggle.querySelector("span").textContent = motionPaused ? "继续运动" : "暂停运动";
    motionToggle.querySelector("path").setAttribute(
      "d",
      motionPaused ? "M8 5v14l11-7z" : "M7 5h4v14H7zm6 0h4v14h-4z",
    );
  }

  function setSearchOpen(open) {
    searchOpen = open;
    searchButton.setAttribute("aria-expanded", String(open));
    searchWrap.hidden = !open;
    sceneDirty = true;
    lastSceneUpload = -Infinity;
    if (open) requestAnimationFrame(() => searchInput.focus());
    else {
      searchInput.value = "";
      searchButton.focus();
    }
  }

  function updateTouch(event) {
    const rect = phoneScreen.getBoundingClientRect();
    touch.x = clamp(event.clientX - rect.left, 0, rect.width);
    touch.y = clamp(event.clientY - rect.top, 0, rect.height);
  }

  function renderFrame(now) {
    const delta = Math.min(50, now - previousFrame);
    previousFrame = now;
    if (!motionPaused) motionClock += delta;
    refreshBurst += (0 - refreshBurst) * (1 - Math.exp(-delta / 520));
    touch.current += (touch.target - touch.current) * (1 - Math.exp(-delta / (touch.target ? 90 : 180)));
    surfaceMotion.forEach((state) => {
      state.current += (state.target - state.current) * (1 - Math.exp(-delta / (state.target ? 90 : 190)));
    });

    const size = syncCanvasSize();
    if (size.width > 0 && size.height > 0) {
      const shouldUpdateScene = sceneDirty || (!motionPaused && now - lastSceneUpload >= 33);
      if (shouldUpdateScene) {
        drawScene(sourceContext, size.cssWidth, size.cssHeight, size.dpr, motionClock, refreshBurst);
        if (renderer) renderer.upload(sourceCanvas);
        lastSceneUpload = now;
        sceneDirty = false;
      }

      if (renderer) {
        renderer.draw({
          width: size.width,
          height: size.height,
          dpr: size.dpr,
          dark: root.dataset.theme === "dark",
          optics: [controls.blur.value, controls.edge.value, controls.bend.value, controls.dispersion.value],
          surfaces: collectSurfaces(size.dpr),
          touch: [touch.x * size.dpr, touch.y * size.dpr, touch.current],
        });
      } else {
        const fallbackContext = canvas.getContext("2d", { alpha: false });
        fallbackContext.setTransform(1, 0, 0, 1, 0, 0);
        fallbackContext.drawImage(sourceCanvas, 0, 0);
      }
    }

    frameHandle = requestAnimationFrame(renderFrame);
  }

  function syncCanvasSize() {
    const rect = phoneScreen.getBoundingClientRect();
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    const width = Math.max(1, Math.round(rect.width * dpr));
    const height = Math.max(1, Math.round(rect.height * dpr));
    if (canvas.width !== width || canvas.height !== height) {
      canvas.width = width;
      canvas.height = height;
      sourceCanvas.width = width;
      sourceCanvas.height = height;
      sceneDirty = true;
      lastSceneUpload = -Infinity;
    }
    return { width, height, cssWidth: rect.width, cssHeight: rect.height, dpr };
  }

  function collectSurfaces(dpr) {
    const screenRect = phoneScreen.getBoundingClientRect();
    return glassElements
      .filter((element) => {
        const rect = element.getBoundingClientRect();
        return !element.hidden && rect.width > 0 && rect.height > 0;
      })
      .slice(0, 8)
      .map((element) => {
        const rect = element.getBoundingClientRect();
        const style = getComputedStyle(element);
        const radiusCss = element.hasAttribute("data-circle")
          ? Math.min(rect.width, rect.height) / 2
          : parseFloat(style.borderTopLeftRadius) || 0;
        const material = element.dataset.material === "reader" ? 1 : 0;
        const clear = element.dataset.clear === "true";
        const imageReady = backdropImage.complete && backdropImage.naturalWidth > 0;
        const fillAlpha = clear
          ? imageReady ? (root.dataset.theme === "dark" ? 0.12 : 0.16) : 0
          : material
            ? imageReady ? (root.dataset.theme === "dark" ? 0.26 : 0.18) : (root.dataset.theme === "dark" ? 0.23 : 0.075)
            : imageReady ? (root.dataset.theme === "dark" ? 0.19 : 0.13) : 0.05;
        return {
          rect: [
            (rect.left - screenRect.left) * dpr,
            (rect.top - screenRect.top) * dpr,
            rect.width * dpr,
            rect.height * dpr,
          ],
          params: [radiusCss * dpr, material, surfaceMotion.get(element).current, fillAlpha],
        };
      });
  }

  function drawScene(context, width, height, dpr, timeMs, burst) {
    const dark = root.dataset.theme === "dark";
    const phase = (timeMs / 32000) * Math.PI * 2 + burst * 1.8;
    const imageReady = backdropImage.complete && backdropImage.naturalWidth > 0;
    context.setTransform(dpr, 0, 0, dpr, 0, 0);
    context.clearRect(0, 0, width, height);

    if (imageReady) {
      const zoom = 1.035 + Math.sin(phase * 0.47) * 0.009;
      const focusX = 0.51 + Math.sin(phase * 0.31) * 0.012;
      const focusY = 0.50 + Math.cos(phase * 0.27) * 0.008;
      drawCoverImage(context, backdropImage, width, height, focusX, focusY, zoom);

      const tone = context.createLinearGradient(0, 0, 0, height);
      if (dark) {
        tone.addColorStop(0, "rgba(2,8,15,.38)");
        tone.addColorStop(0.48, "rgba(3,10,17,.50)");
        tone.addColorStop(1, "rgba(2,6,12,.60)");
      } else {
        tone.addColorStop(0, "rgba(248,246,232,.05)");
        tone.addColorStop(0.52, "rgba(245,242,223,.10)");
        tone.addColorStop(1, "rgba(238,235,213,.16)");
      }
      context.fillStyle = tone;
      context.fillRect(0, 0, width, height);

      const sweepX = width * (0.18 + (Math.sin(phase * 0.61) * 0.5 + 0.5) * 0.64);
      const sunlight = context.createLinearGradient(sweepX - width * 0.34, 0, sweepX + width * 0.24, height);
      sunlight.addColorStop(0, "rgba(255,255,255,0)");
      sunlight.addColorStop(0.46, dark ? "rgba(109,183,255,.035)" : "rgba(255,246,183,.105)");
      sunlight.addColorStop(0.58, dark ? "rgba(123,195,255,.055)" : "rgba(255,255,224,.15)");
      sunlight.addColorStop(1, "rgba(255,255,255,0)");
      context.fillStyle = sunlight;
      context.fillRect(0, 0, width, height);

      const vignette = context.createRadialGradient(
        width * 0.48,
        height * 0.43,
        width * 0.12,
        width * 0.48,
        height * 0.43,
        Math.max(width, height) * 0.72,
      );
      vignette.addColorStop(0, "rgba(0,0,0,0)");
      vignette.addColorStop(1, dark ? "rgba(0,4,10,.34)" : "rgba(24,57,32,.08)");
      context.fillStyle = vignette;
      context.fillRect(0, 0, width, height);
    } else {
      const background = context.createLinearGradient(0, 0, 0, height);
      if (dark) {
        background.addColorStop(0, "#0b1220");
        background.addColorStop(0.55, "#080d16");
        background.addColorStop(1, "#05070a");
      } else {
        background.addColorStop(0, "#faf8f2");
        background.addColorStop(0.58, "#f3f0e8");
        background.addColorStop(1, "#f6f2e9");
      }
      context.fillStyle = background;
      context.fillRect(0, 0, width, height);

      const washes = dark
        ? ["rgba(51,156,255,.09)", "rgba(29,78,216,.07)", "rgba(91,71,170,.055)"]
        : ["rgba(183,193,184,.2)", "rgba(213,199,184,.2)", "rgba(232,223,200,.22)"];
      const washPositions = [
        [0.08 + Math.sin(phase) * 0.10, 0.18 + Math.cos(phase * 0.74) * 0.05, 0.82],
        [0.72 + Math.cos(phase * 0.58) * 0.12, 0.38 + Math.sin(phase * 0.83) * 0.08, 0.72],
        [0.24 + Math.sin(phase * 0.43 + 2) * 0.08, 0.82 + Math.cos(phase * 0.66) * 0.05, 0.9],
      ];
      washPositions.forEach(([x, y, scale], index) => {
        const radius = width * scale;
        const gradient = context.createRadialGradient(x * width, y * height, 0, x * width, y * height, radius);
        gradient.addColorStop(0, washes[index]);
        gradient.addColorStop(0.56, washes[index].replace(/\.[0-9]+\)$/, ".045)"));
        gradient.addColorStop(1, "rgba(0,0,0,0)");
        context.fillStyle = gradient;
        context.fillRect(0, 0, width, height);
      });

      const gridMinor = dark ? "rgba(255,255,255,.025)" : "rgba(92,98,92,.06)";
      const gridMajor = dark ? "rgba(255,255,255,.042)" : "rgba(85,92,86,.085)";
      const spacing = 28;
      context.save();
      context.translate((Math.sin(phase) * 4) % spacing, 0);
      for (let x = -spacing, index = 0; x <= width + spacing; x += spacing, index += 1) {
        context.strokeStyle = index % 4 === 0 ? gridMajor : gridMinor;
        context.lineWidth = index % 4 === 0 ? 0.7 : 0.45;
        context.beginPath();
        context.moveTo(x, 0);
        context.lineTo(x, height);
        context.stroke();
      }
      for (let y = 0, index = 0; y <= height; y += spacing, index += 1) {
        context.strokeStyle = index % 4 === 0 ? gridMajor : gridMinor;
        context.lineWidth = index % 4 === 0 ? 0.7 : 0.45;
        context.beginPath();
        context.moveTo(-spacing, y);
        context.lineTo(width + spacing, y);
        context.stroke();
      }
      context.restore();
    }

    drawLibraryContent(context, width, height, dark, phase, imageReady);
    context.setTransform(1, 0, 0, 1, 0, 0);
  }

  function drawLibraryContent(context, width, height, dark, phase, complexBackdrop) {
    const ink = dark ? "rgba(255,255,255,.94)" : "#262019";
    const soft = dark ? "rgba(255,255,255,.58)" : "rgba(67,63,57,.64)";
    const line = dark ? "rgba(255,255,255,.075)" : "rgba(79,82,76,.12)";
    const accent = dark ? "#73b7ff" : "#17382c";
    const top = searchOpen ? 176 : 126;
    const query = searchInput.value.trim().toLowerCase();
    const books = [
      { title: "长安的荔枝", author: "马伯庸", progress: 0.68, mark: "荔", colors: ["#b75b3e", "#6e2a1d"] },
      { title: "山茶文具店", author: "小川糸", progress: 0.31, mark: "茶", colors: ["#6f8a6b", "#344d3c"] },
      { title: "人类群星闪耀时", author: "斯蒂芬·茨威格", progress: 0.82, mark: "星", colors: ["#4d7094", "#263a55"] },
      { title: "我与地坛", author: "史铁生", progress: 0.16, mark: "坛", colors: ["#ad8462", "#654933"] },
      { title: "月亮与六便士", author: "毛姆", progress: 0.45, mark: "月", colors: ["#746a9f", "#3a355b"] },
    ].filter((book) => !query || book.title.toLowerCase().includes(query) || book.author.toLowerCase().includes(query));

    if (complexBackdrop) {
      const veil = context.createLinearGradient(0, top - 18, 0, height);
      if (dark) {
        veil.addColorStop(0, "rgba(2,8,14,.72)");
        veil.addColorStop(0.52, "rgba(3,9,15,.61)");
        veil.addColorStop(1, "rgba(3,8,13,.49)");
      } else {
        veil.addColorStop(0, "rgba(250,248,239,.78)");
        veil.addColorStop(0.52, "rgba(249,247,237,.68)");
        veil.addColorStop(1, "rgba(246,244,231,.54)");
      }
      context.fillStyle = veil;
      roundRect(context, 8, top - 18, width - 16, height - top + 42, 26);
      context.fill();
    }

    context.fillStyle = accent;
    roundRect(context, 18, top + 2, 4, 18, 2);
    context.fill();
    context.fillStyle = ink;
    context.font = `700 17px ${fontSerif()}`;
    context.fillText(query ? "搜索结果" : "最近阅读", 31, top + 17);
    context.fillStyle = soft;
    context.font = `500 10px ${fontSans()}`;
    context.fillText(`${books.length} 本书 · 按阅读进度排序`, 18, top + 37);

    if (books.length === 0) {
      context.fillStyle = soft;
      context.font = `500 13px ${fontSans()}`;
      context.textAlign = "center";
      context.fillText("没有找到匹配的书", width / 2, top + 130);
      context.textAlign = "left";
      return;
    }

    const rowStart = top + 56;
    const rowHeight = 112;
    books.forEach((book, index) => {
      const y = rowStart + index * rowHeight;
      const parallax = Math.sin(phase * 0.7 + index * 0.8) * 1.6;
      if (y > height - 18) return;

      const coverGradient = context.createLinearGradient(18, y, 68, y + 78);
      coverGradient.addColorStop(0, book.colors[0]);
      coverGradient.addColorStop(1, book.colors[1]);
      context.save();
      context.shadowColor = dark ? "rgba(0,0,0,.4)" : "rgba(61,45,31,.18)";
      context.shadowBlur = 12;
      context.shadowOffsetY = 5;
      context.fillStyle = coverGradient;
      roundRect(context, 18 + parallax, y, 54, 78, 7);
      context.fill();
      context.restore();
      context.fillStyle = "rgba(255,255,255,.13)";
      roundRect(context, 21 + parallax, y, 3, 78, 2);
      context.fill();
      context.fillStyle = "rgba(255,248,237,.94)";
      context.font = `700 18px ${fontSerif()}`;
      context.textAlign = "center";
      context.fillText(book.mark, 45 + parallax, y + 45);
      context.textAlign = "left";

      context.fillStyle = ink;
      context.font = `700 15px ${fontSerif()}`;
      context.fillText(book.title, 88, y + 23);
      context.fillStyle = soft;
      context.font = `500 10px ${fontSans()}`;
      context.fillText(book.author, 88, y + 43);
      context.fillText(`已读 ${Math.round(book.progress * 100)}%`, 88, y + 67);

      const barWidth = Math.max(64, width - 176);
      context.fillStyle = line;
      roundRect(context, 88, y + 75, barWidth, 2, 1);
      context.fill();
      context.fillStyle = accent;
      context.globalAlpha = 0.58;
      roundRect(context, 88, y + 75, barWidth * book.progress, 2, 1);
      context.fill();
      context.globalAlpha = 1;

      context.strokeStyle = line;
      context.lineWidth = 1;
      context.beginPath();
      context.moveTo(88, y + 94.5);
      context.lineTo(width - 18, y + 94.5);
      context.stroke();
    });
  }

  function drawCoverImage(context, image, width, height, focusX, focusY, zoom) {
    const scale = Math.max(width / image.naturalWidth, height / image.naturalHeight) * zoom;
    const sourceWidth = width / scale;
    const sourceHeight = height / scale;
    const sourceX = clamp(image.naturalWidth * focusX - sourceWidth / 2, 0, image.naturalWidth - sourceWidth);
    const sourceY = clamp(image.naturalHeight * focusY - sourceHeight / 2, 0, image.naturalHeight - sourceHeight);
    context.drawImage(
      image,
      sourceX,
      sourceY,
      sourceWidth,
      sourceHeight,
      0,
      0,
      width,
      height,
    );
  }

  function roundRect(context, x, y, width, height, radius) {
    const r = Math.min(radius, width / 2, height / 2);
    context.beginPath();
    context.moveTo(x + r, y);
    context.arcTo(x + width, y, x + width, y + height, r);
    context.arcTo(x + width, y + height, x, y + height, r);
    context.arcTo(x, y + height, x, y, r);
    context.arcTo(x, y, x + width, y, r);
    context.closePath();
  }

  function fontSerif() {
    return '"Songti SC", "Noto Serif CJK SC", STSong, SimSun, serif';
  }

  function fontSans() {
    return '"Microsoft YaHei UI", "PingFang SC", "Noto Sans CJK SC", sans-serif';
  }

  function clamp(value, minimum, maximum) {
    return Math.min(maximum, Math.max(minimum, value));
  }

  function createRenderer(targetCanvas) {
    const gl = targetCanvas.getContext("webgl2", {
      alpha: false,
      antialias: false,
      depth: false,
      stencil: false,
      premultipliedAlpha: false,
      preserveDrawingBuffer: false,
    });
    if (!gl) return null;

    const vertexSource = `#version 300 es
      in vec2 a_position;
      void main() {
        gl_Position = vec4(a_position, 0.0, 1.0);
      }
    `;

    const fragmentSource = `#version 300 es
      precision highp float;

      #define MAX_SURFACES 8
      uniform sampler2D u_scene;
      uniform vec2 u_resolution;
      uniform float u_dpr;
      uniform float u_dark;
      uniform int u_surfaceCount;
      uniform vec4 u_rects[MAX_SURFACES];
      uniform vec4 u_params[MAX_SURFACES];
      uniform vec4 u_optics;
      uniform vec3 u_touch;
      out vec4 outColor;

      float roundedRectDistance(vec2 p, vec2 halfSize, float radius) {
        vec2 q = abs(p) - (halfSize - vec2(radius));
        return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
      }

      vec2 sdfGradient(vec2 p, vec2 halfSize, float radius) {
        float epsilon = max(1.0, u_dpr * 0.5);
        float dx = roundedRectDistance(p + vec2(epsilon, 0.0), halfSize, radius) -
          roundedRectDistance(p - vec2(epsilon, 0.0), halfSize, radius);
        float dy = roundedRectDistance(p + vec2(0.0, epsilon), halfSize, radius) -
          roundedRectDistance(p - vec2(0.0, epsilon), halfSize, radius);
        return normalize(vec2(dx, dy) + vec2(0.00001));
      }

      vec3 sampleBackdrop(vec2 pixel) {
        vec2 safePixel = clamp(pixel, vec2(0.5), u_resolution - vec2(0.5));
        vec3 color = texture(u_scene, safePixel / u_resolution).rgb;
        float radius = max(u_resolution.x, u_resolution.y) * 0.24;
        float lightDistance = distance(safePixel, u_touch.xy);
        float glow = pow(clamp(1.0 - lightDistance / radius, 0.0, 1.0), 2.2) * u_touch.z;
        color += vec3(1.0, 0.995, 0.97) * glow * 0.32;
        return color;
      }

      vec3 blurBackdrop(vec2 pixel, float radius) {
        if (radius < 0.2) return sampleBackdrop(pixel);
        vec2 axis = vec2(radius, 0.0);
        vec2 diagonal = vec2(radius * 0.68);
        vec3 color = sampleBackdrop(pixel) * 0.22;
        color += sampleBackdrop(pixel + axis) * 0.105;
        color += sampleBackdrop(pixel - axis) * 0.105;
        color += sampleBackdrop(pixel + axis.yx) * 0.105;
        color += sampleBackdrop(pixel - axis.yx) * 0.105;
        color += sampleBackdrop(pixel + diagonal) * 0.09;
        color += sampleBackdrop(pixel - diagonal) * 0.09;
        color += sampleBackdrop(pixel + vec2(diagonal.x, -diagonal.y)) * 0.09;
        color += sampleBackdrop(pixel + vec2(-diagonal.x, diagonal.y)) * 0.09;
        return color;
      }

      vec3 renderGlass(vec2 pixel, vec4 rect, vec4 params) {
        vec2 halfSize = rect.zw * 0.5;
        vec2 centered = pixel - rect.xy - halfSize;
        float safeRadius = max(0.0, min(params.x, min(halfSize.x, halfSize.y) - 0.5));
        float distanceToEdge = roundedRectDistance(centered, halfSize, safeRadius);
        float insideDistance = max(-distanceToEdge, 0.0);
        float reader = step(0.5, params.y);
        float interaction = clamp(params.z, 0.0, 1.0);
        float blurRadius = u_optics.x * u_dpr * mix(1.0, 1.5556, reader);
        float edgeBand = u_optics.y * u_dpr * mix(1.0, 1.30, reader);
        float bend = u_optics.z * u_dpr * mix(1.0, 1.2727, reader);
        float dispersion = u_optics.w * u_dpr * mix(1.0, 1.7778, reader);
        bend = min(bend, edgeBand * 0.58);
        dispersion = min(dispersion, min(bend * 0.06, 0.5 * u_dpr));

        float edgeWeight = 1.0 - smoothstep(0.0, max(edgeBand, 0.5), insideDistance);
        vec2 normal = sdfGradient(centered, halfSize, safeRadius);
        float normalizedDepth = clamp(insideDistance / max(edgeBand, 0.5), 0.0, 1.0);
        float shoulder = smoothstep(0.08, 0.22, normalizedDepth) *
          (1.0 - smoothstep(0.34, 0.58, normalizedDepth));
        float activeBend = bend * (1.0 + interaction * 0.15);
        float activeDispersion = dispersion * (1.0 + interaction * 0.20);
        vec2 refractionOffset = normal * activeBend * (edgeWeight * edgeWeight + shoulder * 0.14);
        vec2 refracted = pixel - refractionOffset;

        vec3 center = blurBackdrop(refracted, blurRadius);
        float red = blurBackdrop(refracted - normal * activeDispersion, blurRadius).r;
        float blue = blurBackdrop(refracted + normal * activeDispersion, blurRadius).b;
        vec3 color = vec3(red, center.g, blue);

        vec2 lightDirection = normalize(vec2(-0.72, -0.69));
        float facing = max(dot(normal, lightDirection), 0.0);
        float opposite = max(dot(normal, -lightDirection), 0.0);
        float foldWeight = pow(edgeWeight, 2.8) * 0.12;
        vec3 folded = blurBackdrop(
          refracted - normal * activeBend * (0.75 + edgeWeight * 0.70),
          blurRadius
        );
        color = mix(color, folded, foldWeight);
        float returnWeight = pow(edgeWeight, 3.2) * opposite * 0.075;
        vec3 depthReturn = blurBackdrop(
          pixel - normal * activeBend * 2.05 + lightDirection * activeBend * 0.28,
          blurRadius
        );
        color = mix(color, depthReturn, returnWeight);

        float localContrast = 1.0 + edgeWeight * 0.10;
        color = (color - vec3(0.5)) * localContrast + vec3(0.5);

        float ior = 1.47;
        float f0 = (ior - 1.0) / (ior + 1.0);
        f0 *= f0;
        float cosTheta = clamp(abs(dot(normal, lightDirection)), 0.0, 1.0);
        float fresnel = f0 + (1.0 - f0) * pow(1.0 - cosTheta, 5.0);
        float specularStrength = mix(0.17, 0.145, u_dark) * (1.0 + interaction * 0.10);
        float innerRidge = smoothstep(0.07, 0.18, normalizedDepth) *
          (1.0 - smoothstep(0.28, 0.43, normalizedDepth));
        float highlight = edgeWeight * fresnel * (0.30 + 0.70 * facing) * specularStrength * 3.2 +
          innerRidge * facing * specularStrength * 0.34;
        float coolReturn = edgeWeight * pow(opposite, 2.1) * specularStrength * 0.26;
        color += highlight * vec3(1.0, 0.995, 0.98);
        color += coolReturn * vec3(0.80, 1.0, 0.94);
        float depthShadow = (edgeWeight * 0.65 + innerRidge * 0.48) *
          pow(opposite, 1.8) * specularStrength * 0.34;
        color -= depthShadow * vec3(0.24, 0.22, 0.18);

        vec3 tint = mix(vec3(0.98, 0.973, 0.949), vec3(0.055, 0.09, 0.15), u_dark);
        color = mix(color, tint, params.w);

        float contour = 1.0 - smoothstep(0.0, max(1.1 * u_dpr, 1.0), insideDistance);
        vec3 contourLight = mix(
          mix(vec3(0.62, 0.73, 0.68), vec3(0.39, 0.77, 1.0), u_dark),
          vec3(1.0),
          facing * 0.7
        );
        color += contourLight * contour * mix(0.11, 0.15, u_dark);
        return clamp(color, 0.0, 1.0);
      }

      void main() {
        vec2 pixel = vec2(gl_FragCoord.x, u_resolution.y - gl_FragCoord.y);
        vec3 color = sampleBackdrop(pixel);
        for (int index = 0; index < MAX_SURFACES; index += 1) {
          if (index >= u_surfaceCount) break;
          vec4 rect = u_rects[index];
          vec2 halfSize = rect.zw * 0.5;
          vec2 centered = pixel - rect.xy - halfSize;
          float radius = min(u_params[index].x, min(halfSize.x, halfSize.y) - 0.5);
          if (roundedRectDistance(centered, halfSize, max(radius, 0.0)) <= 0.0) {
            color = renderGlass(pixel, rect, u_params[index]);
            break;
          }
        }
        outColor = vec4(color, 1.0);
      }
    `;

    try {
      const program = createProgram(gl, vertexSource, fragmentSource);
      const position = gl.getAttribLocation(program, "a_position");
      const buffer = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);

      const texture = gl.createTexture();
      gl.bindTexture(gl.TEXTURE_2D, texture);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);

      const locations = {
        scene: gl.getUniformLocation(program, "u_scene"),
        resolution: gl.getUniformLocation(program, "u_resolution"),
        dpr: gl.getUniformLocation(program, "u_dpr"),
        dark: gl.getUniformLocation(program, "u_dark"),
        surfaceCount: gl.getUniformLocation(program, "u_surfaceCount"),
        rects: gl.getUniformLocation(program, "u_rects[0]"),
        params: gl.getUniformLocation(program, "u_params[0]"),
        optics: gl.getUniformLocation(program, "u_optics"),
        touch: gl.getUniformLocation(program, "u_touch"),
      };

      gl.useProgram(program);
      gl.enableVertexAttribArray(position);
      gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
      gl.vertexAttribPointer(position, 2, gl.FLOAT, false, 0, 0);
      gl.uniform1i(locations.scene, 0);

      return {
        upload(image) {
          gl.activeTexture(gl.TEXTURE0);
          gl.bindTexture(gl.TEXTURE_2D, texture);
          gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
          gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, image);
        },
        draw(state) {
          gl.viewport(0, 0, state.width, state.height);
          gl.useProgram(program);
          gl.uniform2f(locations.resolution, state.width, state.height);
          gl.uniform1f(locations.dpr, state.dpr);
          gl.uniform1f(locations.dark, state.dark ? 1 : 0);
          gl.uniform4f(
            locations.optics,
            state.optics[0], state.optics[1], state.optics[2], state.optics[3],
          );
          gl.uniform3f(locations.touch, state.touch[0], state.touch[1], state.touch[2]);
          gl.uniform1i(locations.surfaceCount, state.surfaces.length);

          const rects = new Float32Array(8 * 4);
          const params = new Float32Array(8 * 4);
          state.surfaces.forEach((surface, index) => {
            rects.set(surface.rect, index * 4);
            params.set(surface.params, index * 4);
          });
          gl.uniform4fv(locations.rects, rects);
          gl.uniform4fv(locations.params, params);
          gl.drawArrays(gl.TRIANGLES, 0, 3);
        },
      };
    } catch (error) {
      console.error("Liquid Glass WebGL renderer could not start:", error);
      return null;
    }
  }

  function createProgram(gl, vertexSource, fragmentSource) {
    const vertexShader = compileShader(gl, gl.VERTEX_SHADER, vertexSource);
    const fragmentShader = compileShader(gl, gl.FRAGMENT_SHADER, fragmentSource);
    const program = gl.createProgram();
    gl.attachShader(program, vertexShader);
    gl.attachShader(program, fragmentShader);
    gl.linkProgram(program);
    if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
      throw new Error(gl.getProgramInfoLog(program) || "WebGL program link failed");
    }
    gl.deleteShader(vertexShader);
    gl.deleteShader(fragmentShader);
    return program;
  }

  function compileShader(gl, type, source) {
    const shader = gl.createShader(type);
    gl.shaderSource(shader, source);
    gl.compileShader(shader);
    if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
      const message = gl.getShaderInfoLog(shader) || "WebGL shader compile failed";
      gl.deleteShader(shader);
      throw new Error(message);
    }
    return shader;
  }

  window.addEventListener("beforeunload", () => cancelAnimationFrame(frameHandle), { once: true });
})();

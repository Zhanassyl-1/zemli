// Bootstrap state loader from Telegram WebApp.
(function() {
  console.log("🚀 Карта запускается...");

  // Получаем Telegram WebApp
  const tg = window.Telegram?.WebApp;
  if (!tg) {
    console.error("❌ Telegram WebApp не найден");
    return;
  }

  // Получаем ID пользователя
  const playerId = tg.initDataUnsafe?.user?.id;
  console.log("🎮 ID игрока:", playerId);

  if (!playerId) {
    alert("Ошибка: не удалось получить ID пользователя");
    return;
  }

  window.__tg = tg;
  window.__playerId = playerId;

  // Загружаем состояние игрока
  async function loadGameState() {
    try {
      const response = await fetch(`/api/game/state?playerId=${playerId}`);
      const data = await response.json();
      console.log("📦 Загружено состояние:", data);

      // Сохраняем в глобальные переменные
      window.playerData = data;

      // Если есть buildings — рисуем
      if (data.buildings) {
        drawBuildings(data.buildings);
      }
    } catch (error) {
      console.error("❌ Ошибка загрузки:", error);
    }
  }

  // Ждём готовности и загружаем
  tg.ready();
  loadGameState();
})();

// Rich tactical map UI for Telegram WebApp.
const TILE_SIZE = 32;
const MAP_WIDTH = 2000;
const MAP_HEIGHT = 2000;
const CENTER_X = Math.floor(MAP_WIDTH / 2);
const CENTER_Y = Math.floor(MAP_HEIGHT / 2);

const API_BASE = "";
const tg = window.__tg || window.Telegram?.WebApp;
const playerId = window.__playerId || tg?.initDataUnsafe?.user?.id || 0;
const TELEGRAM_USER_ID = Number(playerId || 0);
const URL_PLAYER_ID = Number(new URLSearchParams(window.location.search).get("playerId") || 0);
const ACTIVE_PLAYER_ID = URL_PLAYER_ID || TELEGRAM_USER_ID;
const USER_KEY = TELEGRAM_USER_ID > 0 ? String(TELEGRAM_USER_ID) : "guest";

let cameraX = CENTER_X * TILE_SIZE - window.innerWidth / 2;
let cameraY = CENTER_Y * TILE_SIZE - window.innerHeight / 2;
let scale = 0.75;

let mapCanvas = null;
let mapCtx = null;
let isDragging = false;
let lastPointerX = 0;
let lastPointerY = 0;
let touchMode = "none";
let pinchStartDistance = 0;
let pinchStartScale = 1;

let actionMode = null; // build | move | attack | army | null
let selectedBuilding = null;
let selectedUnitType = null;
let armyOrderMode = "recruit"; // recruit | move

let loadedBuildings = [];
let inventory = {};
let gameResources = null;
let loadedUnits = [];
let enemyMarkers = [];
let resourceNodes = [];
let dirty = true;

const VIEW_RADIUS = 15;
const FOG_COLOR = "#0f1720";
const playerHome = { x: 0, y: 0 };

const buildingEmojiMap = {
  capitol: "🏰",
  lumber: "🪓",
  mine: "⛏️",
  farm: "🌾",
  barracks: "⚔️",
  iron_mine: "⚔️",
  warehouse: "📦",
  house: "🏠",
  wall: "🧱",
  tower: "🗼",
  gold: "💰",
  stable: "🐎",
  library: "📚",
  tavern: "🍺"
};

const unitEmojiMap = {
  infantry: "🛡️",
  archer: "🏹",
  cavalry: "🐎",
  hero: "🧙"
};

const BIOME = {
  OCEAN: 0,
  SHALLOW: 1,
  DESERT: 2,
  PLAINS: 3,
  FOREST: 4,
  JUNGLE: 5,
  MOUNTAIN: 6,
  SNOW: 7
};

const COLORS = {
  [BIOME.OCEAN]: "#0a2f6a",
  [BIOME.SHALLOW]: "#1e6fb0",
  [BIOME.DESERT]: "#e9b35f",
  [BIOME.PLAINS]: "#90be6d",
  [BIOME.FOREST]: "#2d6a4f",
  [BIOME.JUNGLE]: "#1b4d1b",
  [BIOME.MOUNTAIN]: "#8b7d6b",
  [BIOME.SNOW]: "#e9ecef"
};

const biomeMap = new Uint8Array(MAP_WIDTH * MAP_HEIGHT);
const elevationMap = new Float32Array(MAP_WIDTH * MAP_HEIGHT);

if (tg) {
  tg.expand();
  tg.enableClosingConfirmation();
  tg.setHeaderColor("#1a1a1a");
  tg.setBackgroundColor("#1a1a1a");
  tg.ready();
  loadGame();
}

function idx(x, y) {
  return y * MAP_WIDTH + x;
}

function fract(v) {
  return v - Math.floor(v);
}

function hash2(x, y, seed) {
  const n = Math.sin((x * 127.1 + y * 311.7 + seed * 13.37)) * 43758.5453123;
  return fract(n);
}

function smoothNoise(x, y, seed) {
  const x0 = Math.floor(x);
  const y0 = Math.floor(y);
  const x1 = x0 + 1;
  const y1 = y0 + 1;
  const tx = x - x0;
  const ty = y - y0;

  const u = tx * tx * (3 - 2 * tx);
  const v = ty * ty * (3 - 2 * ty);

  const n00 = hash2(x0, y0, seed);
  const n10 = hash2(x1, y0, seed);
  const n01 = hash2(x0, y1, seed);
  const n11 = hash2(x1, y1, seed);

  const nx0 = n00 * (1 - u) + n10 * u;
  const nx1 = n01 * (1 - u) + n11 * u;
  return nx0 * (1 - v) + nx1 * v;
}

function fbm(x, y, octaves, baseFreq, lacunarity, gain, seed) {
  let amp = 1;
  let freq = baseFreq;
  let sum = 0;
  let norm = 0;
  for (let i = 0; i < octaves; i++) {
    sum += smoothNoise(x * freq, y * freq, seed + i * 101) * amp;
    norm += amp;
    amp *= gain;
    freq *= lacunarity;
  }
  return sum / norm;
}

function generateElevationAndThreshold() {
  const bins = 2048;
  const hist = new Uint32Array(bins);
  let minV = Infinity;
  let maxV = -Infinity;

  for (let y = 0; y < MAP_HEIGHT; y++) {
    const ny = (y - CENTER_Y) / MAP_HEIGHT;
    for (let x = 0; x < MAP_WIDTH; x++) {
      const nx = (x - CENTER_X) / MAP_WIDTH;
      const continental = fbm(nx * 7.0, ny * 7.0, 5, 1.0, 2.0, 0.5, 17) * 2 - 1;
      const regional = fbm(nx * 16.0, ny * 16.0, 4, 1.0, 2.1, 0.52, 71) * 2 - 1;
      const local = fbm(nx * 40.0, ny * 40.0, 3, 1.0, 2.2, 0.55, 131) * 2 - 1;
      const edgeDist = Math.sqrt((nx * 1.1) * (nx * 1.1) + (ny * 0.95) * (ny * 0.95));
      const edgeMask = 1.0 - Math.max(0, edgeDist - 0.35) * 0.9;
      const e = (continental * 0.62 + regional * 0.28 + local * 0.10) * edgeMask;
      const id = idx(x, y);
      elevationMap[id] = e;
      if (e < minV) minV = e;
      if (e > maxV) maxV = e;
    }
  }

  const range = maxV - minV || 1;
  for (let i = 0; i < elevationMap.length; i++) {
    const b = Math.max(0, Math.min(bins - 1, Math.floor(((elevationMap[i] - minV) / range) * (bins - 1))));
    hist[b]++;
  }

  const targetWater = Math.floor(elevationMap.length * 0.40);
  let cum = 0;
  let waterBin = 0;
  for (let b = 0; b < bins; b++) {
    cum += hist[b];
    if (cum >= targetWater) {
      waterBin = b;
      break;
    }
  }

  return minV + (waterBin / (bins - 1)) * range;
}

function assignBiomes(landThreshold) {
  for (let y = 0; y < MAP_HEIGHT; y++) {
    const lat = Math.abs((y - CENTER_Y) / CENTER_Y);
    for (let x = 0; x < MAP_WIDTH; x++) {
      const id = idx(x, y);
      const e = elevationMap[id];

      if (e <= landThreshold) {
        biomeMap[id] = BIOME.OCEAN;
        continue;
      }

      const nx = (x - CENTER_X) / MAP_WIDTH;
      const ny = (y - CENTER_Y) / MAP_HEIGHT;
      const moisture = fbm(nx * 25, ny * 25, 4, 1.0, 2.0, 0.5, 401);
      const mountainNoise = fbm(nx * 30, ny * 30, 3, 1.0, 2.2, 0.55, 777);
      const centerDist = Math.sqrt(nx * nx + ny * ny) / 0.5;
      const desertBias = Math.max(0, 1 - centerDist);
      const temp = 1 - lat;

      if (lat > 0.78) biomeMap[id] = BIOME.SNOW;
      else if (mountainNoise > 0.66 || e > landThreshold + 0.45) biomeMap[id] = BIOME.MOUNTAIN;
      else if (desertBias > 0.55 && temp > 0.62 && moisture < 0.56) biomeMap[id] = BIOME.DESERT;
      else if (temp > 0.68 && moisture > 0.58) biomeMap[id] = BIOME.JUNGLE;
      else if (moisture > 0.57) biomeMap[id] = BIOME.FOREST;
      else biomeMap[id] = BIOME.PLAINS;
    }
  }

  for (let y = 1; y < MAP_HEIGHT - 1; y++) {
    for (let x = 1; x < MAP_WIDTH - 1; x++) {
      const id = idx(x, y);
      if (biomeMap[id] !== BIOME.OCEAN) continue;
      const n1 = biomeMap[idx(x + 1, y)] !== BIOME.OCEAN;
      const n2 = biomeMap[idx(x - 1, y)] !== BIOME.OCEAN;
      const n3 = biomeMap[idx(x, y + 1)] !== BIOME.OCEAN;
      const n4 = biomeMap[idx(x, y - 1)] !== BIOME.OCEAN;
      const n5 = biomeMap[idx(x + 1, y + 1)] !== BIOME.OCEAN;
      const n6 = biomeMap[idx(x - 1, y - 1)] !== BIOME.OCEAN;
      if (n1 || n2 || n3 || n4 || n5 || n6) biomeMap[id] = BIOME.SHALLOW;
    }
  }
}

function generateBiomeMap() {
  console.log("🗺️ Генерация биомов...");
  generateBiomeMap();
  generateResourceNodes();
  console.log("✅ Биомы сгенерированы");
}

function generateResourceNodes() {
  console.log("📦 Генерация ресурсов...");
  resourceNodes = [];
  for (let y = 0; y < MAP_HEIGHT; y += 2) {
    for (let x = 0; x < MAP_WIDTH; x += 2) {
      const biome = biomeMap[idx(x, y)];
      const relX = x - CENTER_X;
      const relY = y - CENTER_Y;
      const h = Math.abs((relX * 73856093) ^ (relY * 19349663));
      let icon = null;
      if (biome === BIOME.FOREST && h % 9 === 0) icon = "🪵";
      else if (biome === BIOME.MOUNTAIN && h % 11 === 0) icon = "⛏️";
      else if (biome === BIOME.PLAINS && h % 13 === 0) icon = "🌾";
      else if (biome === BIOME.DESERT && h % 27 === 0) icon = "💰";
      if (icon) resourceNodes.push({ x: relX, y: relY, icon });
    }
  }
  console.log("✅ Ресурсы сгенерированы:", resourceNodes.length);
}

function requestRender() {
  dirty = true;
}

function isWithinViewRadius(relX, relY) {
  const dx = relX - playerHome.x;
  const dy = relY - playerHome.y;
  return (dx * dx + dy * dy) <= (VIEW_RADIUS * VIEW_RADIUS);
}

function biomeName(code) {
  switch (code) {
    case BIOME.OCEAN: return "Океан";
    case BIOME.SHALLOW: return "Мелководье";
    case BIOME.DESERT: return "Пустыня";
    case BIOME.PLAINS: return "Равнина";
    case BIOME.FOREST: return "Лес";
    case BIOME.JUNGLE: return "Джунгли";
    case BIOME.MOUNTAIN: return "Скалы";
    case BIOME.SNOW: return "Снег";
    default: return "Неизвестно";
  }
}

function normalizeType(raw) {
  return (raw || "").toLowerCase().trim();
}

function resourceAt(relX, relY) {
  const h = Math.abs((relX * 73856093) ^ (relY * 19349663));
  if (h % 67 === 0) return "🪵";
  if (h % 71 === 0) return "⛏️";
  if (h % 79 === 0) return "🌾";
  if (h % 97 === 0) return "💰";
  return null;
}

function generateEnemyMarkers() {
  const markers = [];
  for (let i = 0; i < 20; i++) {
    const x = ((i * 53) % 420) - 210;
    const y = ((i * 97) % 420) - 210;
    markers.push({ x, y });
  }
  return markers;
}

function worldToScreen(relX, relY) {
  return {
    x: ((relX + CENTER_X) * TILE_SIZE * scale) - cameraX,
    y: ((relY + CENTER_Y) * TILE_SIZE * scale) - cameraY
  };
}

function centerOnRelative(relX, relY) {
  const tile = TILE_SIZE * scale;
  cameraX = (relX + CENTER_X + 0.5) * tile - mapCanvas.width / 2;
  cameraY = (relY + CENTER_Y + 0.5) * tile - mapCanvas.height / 2;
}

function findCapitol() {
  const cap = loadedBuildings.find((b) => normalizeType(b.type) === "capitol");
  return cap || null;
}

function ensureStartingHome() {
  const capitol = loadedBuildings.find((b) => normalizeType(b.type) === "capitol");
  const house = loadedBuildings.find((b) => normalizeType(b.type) === "house");
  const home = capitol || house;
  if (home) {
    playerHome.x = Number(home.x || 0);
    playerHome.y = Number(home.y || 0);
    console.log("🏠 Дом загружен:", playerHome);
    return;
  }

  loadedBuildings.push({ x: 0, y: 0, type: "house", ownerId: ACTIVE_PLAYER_ID || 0, local: true });
  window.buildings = loadedBuildings;
  playerHome.x = 0;
  playerHome.y = 0;
  console.log("🏠 Добавлен стартовый дом: (0,0)");
}

async function placeBuilding(x, y, type) {
  if (!ACTIVE_PLAYER_ID) {
    console.warn("No Telegram user id; build is disabled.");
    return false;
  }
  const res = await fetch(`${API_BASE}/api/build`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      playerId: ACTIVE_PLAYER_ID,
      userId: ACTIVE_PLAYER_ID,
      x,
      y,
      type,
      building: type
    })
  });
  if (!res.ok) {
    const msg = await res.text();
    console.warn("Build failed", res.status, msg);
    return false;
  }
  await loadGameState();
  return true;
}

function normalizeInventoryPayload(rawInventory) {
  const result = {};
  if (!rawInventory || typeof rawInventory !== "object") return result;
  for (const [type, count] of Object.entries(rawInventory)) {
    const n = Number(count || 0);
    if (n > 0) result[normalizeType(type)] = n;
  }
  return result;
}

async function loadGame() {
  if (!playerId) return;

  try {
    const res = await fetch(`${API_BASE}/api/game/state?playerId=${playerId}`);
    if (!res.ok) {
      console.warn("State load failed", res.status);
      return;
    }
    const data = await res.json();
    console.log("📦 Загружено:", data);
    loadedBuildings = Array.isArray(data.buildings) ? data.buildings : [];
    inventory = normalizeInventoryPayload(data.inventory || {});
    gameResources = data.resources || null;
    window.buildings = loadedBuildings;
    window.inventory = inventory;
    window.resources = gameResources;
    ensureStartingHome();
    if (mapCanvas) {
      centerOnRelative(playerHome.x, playerHome.y);
    }
    renderInventoryPanel();
    if (mapCtx && mapCanvas) {
      drawMap(mapCtx, mapCanvas);
    }
    requestRender();
  } catch (e) {
    console.error("❌ Ошибка загрузки:", e);
  }
}

async function loadGameState() {
  await loadGame();
}

function unitsStorageKey() {
  return `map_units_${USER_KEY}`;
}

function saveUnits() {
  localStorage.setItem(unitsStorageKey(), JSON.stringify(loadedUnits));
}

function loadUnits() {
  const raw = localStorage.getItem(unitsStorageKey());
  loadedUnits = raw ? JSON.parse(raw) : [];
  if (!Array.isArray(loadedUnits)) loadedUnits = [];
}

function addUnit(type, x, y) {
  const unit = {
    id: `u_${Date.now()}_${Math.floor(Math.random() * 9999)}`,
    type,
    x,
    y,
    owner: USER_KEY
  };
  loadedUnits.push(unit);
  saveUnits();
}

async function moveUnit(unitId, x, y) {
  const unit = loadedUnits.find((u) => u.id === unitId);
  if (!unit) return;

  if (ACTIVE_PLAYER_ID) {
    try {
      await fetch(`${API_BASE}/api/move`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ userId: ACTIVE_PLAYER_ID, x, y, units: 1 })
      });
    } catch (e) {
      console.warn("Move request failed", e);
    }
  }

  unit.x = x;
  unit.y = y;
  saveUnits();
}

async function attack(x, y) {
  // Stub until backend attack endpoint is added.
  console.log(`⚔️ Атака по (${x}, ${y})`);
}

function updateModeText() {
  const modeInfo = document.getElementById("modeInfo");
  if (!modeInfo) return;
  const modeText = actionMode || "наблюдение";
  modeInfo.textContent = `Режим: ${modeText}`;
}

function inventoryTypes() {
  return Object.entries(inventory)
    .filter(([, count]) => Number(count) > 0)
    .map(([type]) => normalizeType(type));
}

function renderInventoryPanel() {
  const wrap = document.getElementById("inventoryButtons");
  const empty = document.getElementById("inventoryEmpty");
  if (!wrap) return;

  wrap.querySelectorAll(".build-btn").forEach((b) => b.remove());

  const types = inventoryTypes();
  if (!types.length) {
    if (empty) empty.style.display = "block";
    selectedBuilding = null;
    return;
  }

  if (empty) empty.style.display = "none";
  for (const type of types) {
    const count = Number(inventory[type] || 0);
    const btn = document.createElement("button");
    btn.className = "emoji-btn build-btn";
    btn.dataset.type = type;
    btn.title = `${type} x${count}`;
    btn.innerHTML = `${buildingEmojiMap[type] || "🏗️"}<span class="inventory-count">x${count}</span>`;
    btn.addEventListener("click", () => {
      document.querySelectorAll(".build-btn").forEach((b) => b.classList.remove("selected"));
      btn.classList.add("selected");
      selectedBuilding = type;
      setActionMode("build");
    });
    wrap.appendChild(btn);
  }
}

function setActionMode(mode) {
  actionMode = mode;
  document.querySelectorAll(".action-btn").forEach((b) => b.classList.remove("active"));
  const map = {
    build: "actionBuild",
    move: "actionMove",
    attack: "actionAttack"
  };
  const activeId = map[mode];
  if (activeId) {
    document.getElementById(activeId)?.classList.add("active");
  }
  updateModeText();
  requestRender();
}

function setArmyOrderMode(mode) {
  armyOrderMode = mode;
  document.getElementById("armyModeRecruit")?.classList.toggle("active", mode === "recruit");
  document.getElementById("armyModeMove")?.classList.toggle("active", mode === "move");
}

function bindUi() {
  document.querySelectorAll(".unit-btn").forEach((btn) => {
    btn.addEventListener("click", () => {
      document.querySelectorAll(".unit-btn").forEach((b) => b.classList.remove("selected"));
      btn.classList.add("selected");
      selectedUnitType = btn.dataset.unit;
      setActionMode("move");
    });
  });

  document.getElementById("actionBuild")?.addEventListener("click", () => setActionMode("build"));
  document.getElementById("actionMove")?.addEventListener("click", () => setActionMode("move"));
  document.getElementById("actionAttack")?.addEventListener("click", () => setActionMode("attack"));

  document.getElementById("actionHome")?.addEventListener("click", () => {
    centerOnRelative(playerHome.x, playerHome.y);
    requestRender();
  });

  document.getElementById("actionCancel")?.addEventListener("click", () => {
    actionMode = null;
    selectedBuilding = null;
    selectedUnitType = null;
    document.querySelectorAll(".action-btn, .build-btn, .unit-btn").forEach((b) => b.classList.remove("active", "selected"));
    updateModeText();
    requestRender();
  });

  document.getElementById("armyModeRecruit")?.addEventListener("click", () => setArmyOrderMode("recruit"));
  document.getElementById("armyModeMove")?.addEventListener("click", () => setArmyOrderMode("move"));

  document.querySelectorAll(".panel-toggle").forEach((btn) => {
    btn.addEventListener("click", () => {
      const panelId = btn.getAttribute("data-panel");
      const panel = document.getElementById(panelId);
      if (!panel) return;
      panel.classList.toggle("panel-collapsed");
      btn.textContent = panel.classList.contains("panel-collapsed") ? "▸" : "▾";
    });
  });

  document.getElementById("zoomIn")?.addEventListener("click", () => {
    scale = Math.min(3.0, scale * 1.2);
    requestRender();
  });
  document.getElementById("zoomOut")?.addEventListener("click", () => {
    scale = Math.max(0.25, scale / 1.2);
    requestRender();
  });

  if (window.innerWidth <= 900) {
    ["buildPanel", "armyPanel"].forEach((id) => {
      const panel = document.getElementById(id);
      if (panel) panel.classList.add("panel-collapsed");
      const toggle = panel?.querySelector(".panel-toggle");
      if (toggle) toggle.textContent = "▸";
    });
  }
}

function getRelativeTileFromScreen(screenX, screenY) {
  const worldX = (screenX + cameraX) / (TILE_SIZE * scale);
  const worldY = (screenY + cameraY) / (TILE_SIZE * scale);
  return {
    x: Math.floor(worldX) - CENTER_X,
    y: Math.floor(worldY) - CENTER_Y
  };
}

function distanceSq(a, b) {
  const dx = a.x - b.x;
  const dy = a.y - b.y;
  return dx * dx + dy * dy;
}

function pickNearestUnit(x, y) {
  if (!loadedUnits.length) return null;
  let best = loadedUnits[0];
  let bestD = distanceSq(best, { x, y });
  for (let i = 1; i < loadedUnits.length; i++) {
    const d = distanceSq(loadedUnits[i], { x, y });
    if (d < bestD) {
      best = loadedUnits[i];
      bestD = d;
    }
  }
  return best;
}

async function onMapClick(relX, relY) {
  if (actionMode === "build" || selectedBuilding) {
    if (!selectedBuilding) {
      const types = inventoryTypes();
      if (!types.length) {
        alert("Инвентарь построек пуст");
        return;
      }
      selectedBuilding = types[0];
    }
    if (!inventory[selectedBuilding] || inventory[selectedBuilding] <= 0) {
      alert("Этой постройки нет в инвентаре");
      renderInventoryPanel();
      return;
    }

    const placed = await placeBuilding(relX, relY, selectedBuilding);
    if (placed) {
      inventory[selectedBuilding] = Math.max(0, Number(inventory[selectedBuilding]) - 1);
      if (inventory[selectedBuilding] === 0) {
        delete inventory[selectedBuilding];
        selectedBuilding = null;
      }
      renderInventoryPanel();
      requestRender();
    }
    return;
  }

  if (!actionMode) return;

  if (actionMode === "move") {
    console.log(`🚚 Переместить в (${relX}, ${relY})`);
    if (selectedUnitType && armyOrderMode === "recruit") {
      addUnit(selectedUnitType, relX, relY);
      requestRender();
      return;
    }
    if (armyOrderMode === "move") {
      const unit = pickNearestUnit(relX, relY);
      if (unit) {
        await moveUnit(unit.id, relX, relY);
        requestRender();
      }
    }
    return;
  }

  if (actionMode === "attack") {
    await attack(relX, relY);
  }
}

function handleMouseDown(e) {
  isDragging = true;
  lastPointerX = e.clientX;
  lastPointerY = e.clientY;
}

function handleMouseMove(e) {
  const rect = mapCanvas.getBoundingClientRect();
  const mx = e.clientX - rect.left;
  const my = e.clientY - rect.top;
  const rel = getRelativeTileFromScreen(mx, my);

  const wx = rel.x + CENTER_X;
  const wy = rel.y + CENTER_Y;
  const inside = wx >= 0 && wy >= 0 && wx < MAP_WIDTH && wy < MAP_HEIGHT;
  const b = inside ? biomeMap[idx(wx, wy)] : BIOME.OCEAN;

  document.getElementById("coords").textContent = `X: ${rel.x}, Y: ${rel.y}`;
  document.getElementById("biomeInfo").textContent = biomeName(b);

  if (!isDragging) return;
  const dx = e.clientX - lastPointerX;
  const dy = e.clientY - lastPointerY;
  cameraX -= dx;
  cameraY -= dy;
  lastPointerX = e.clientX;
  lastPointerY = e.clientY;
  requestRender();
}

function handleWheel(e) {
  e.preventDefault();
  const rect = mapCanvas.getBoundingClientRect();
  const mouseX = e.clientX - rect.left;
  const mouseY = e.clientY - rect.top;

  const worldX = (mouseX + cameraX) / (TILE_SIZE * scale);
  const worldY = (mouseY + cameraY) / (TILE_SIZE * scale);

  scale = e.deltaY < 0 ? Math.min(3.0, scale * 1.1) : Math.max(0.25, scale / 1.1);

  cameraX = (worldX * TILE_SIZE * scale) - mouseX;
  cameraY = (worldY * TILE_SIZE * scale) - mouseY;
  requestRender();
}

function touchDistance(t1, t2) {
  const dx = t1.clientX - t2.clientX;
  const dy = t1.clientY - t2.clientY;
  return Math.sqrt(dx * dx + dy * dy);
}

function handleTouchStart(e) {
  e.preventDefault();
  if (e.touches.length === 1) {
    touchMode = "drag";
    lastPointerX = e.touches[0].clientX;
    lastPointerY = e.touches[0].clientY;
  } else if (e.touches.length === 2) {
    touchMode = "pinch";
    pinchStartDistance = touchDistance(e.touches[0], e.touches[1]);
    pinchStartScale = scale;
  }
}

function handleTouchMove(e) {
  e.preventDefault();
  if (touchMode === "drag" && e.touches.length === 1) {
    const cx = e.touches[0].clientX;
    const cy = e.touches[0].clientY;
    const dx = cx - lastPointerX;
    const dy = cy - lastPointerY;
    cameraX -= dx;
    cameraY -= dy;
    lastPointerX = cx;
    lastPointerY = cy;
    requestRender();
  } else if (touchMode === "pinch" && e.touches.length === 2) {
    const currentDistance = touchDistance(e.touches[0], e.touches[1]);
    if (pinchStartDistance > 0) {
      scale = Math.max(0.25, Math.min(3.0, pinchStartScale * (currentDistance / pinchStartDistance)));
      requestRender();
    }
  }
}

function handleTouchEnd(e) {
  e.preventDefault();
  if (e.touches.length === 0) {
    touchMode = "none";
  }
}

function drawMap(ctx, canvas) {
  const tile = TILE_SIZE * scale;
  if (tile <= 0.01) return null;

  let startCol = Math.floor(cameraX / tile);
  let startRow = Math.floor(cameraY / tile);
  let endCol = startCol + Math.ceil(canvas.width / tile) + 2;
  let endRow = startRow + Math.ceil(canvas.height / tile) + 2;

  startCol = Math.max(0, startCol);
  startRow = Math.max(0, startRow);
  endCol = Math.min(MAP_WIDTH, endCol);
  endRow = Math.min(MAP_HEIGHT, endRow);

  ctx.imageSmoothingEnabled = false;

  for (let row = startRow; row < endRow; row++) {
    const y = (row * tile) - cameraY;
    for (let col = startCol; col < endCol; col++) {
      const x = (col * tile) - cameraX;
      const relX = col - CENTER_X;
      const relY = row - CENTER_Y;
      if (!isWithinViewRadius(relX, relY)) {
        ctx.fillStyle = FOG_COLOR;
        ctx.fillRect(x, y, tile + 1, tile + 1);
        continue;
      }
      const b = biomeMap[idx(col, row)];
      ctx.fillStyle = COLORS[b] || "#90be6d";
      ctx.fillRect(x, y, tile + 1, tile + 1);
    }
  }

  return { startCol, startRow, endCol, endRow, tile };
}

function drawResourceMarkers(ctx, view) {
  const { tile } = view;
  ctx.textAlign = "center";
  ctx.textBaseline = "middle";
  ctx.font = `${Math.max(11, tile * 0.45)}px "Apple Color Emoji","Segoe UI Emoji","Noto Color Emoji",sans-serif`;

  for (const node of resourceNodes) {
    if (!isWithinViewRadius(node.x, node.y)) continue;
    const pos = worldToScreen(node.x, node.y);
    if (pos.x + tile < -40 || pos.y + tile < -40 || pos.x > mapCanvas.width + 40 || pos.y > mapCanvas.height + 40) continue;
    ctx.fillText(node.icon, pos.x + tile / 2, pos.y + tile / 2);
  }
}

function drawBuildings(ctx, view) {
  if (Array.isArray(ctx)) {
    loadedBuildings = ctx;
    window.buildings = loadedBuildings;
    return;
  }
  const { tile } = view;
  const buildings = window.buildings || loadedBuildings || [];
  if (!buildings.length) return;

  for (const b of buildings) {
    const pos = worldToScreen(b.x, b.y);
    if (pos.x + tile < -60 || pos.y + tile < -60 || pos.x > mapCanvas.width + 60 || pos.y > mapCanvas.height + 60) continue;

    const emoji = buildingEmojiMap[normalizeType(b.type)] || "🏠";
    const stickerX = pos.x + tile / 2;
    const stickerY = pos.y + tile / 2;

    ctx.beginPath();
    ctx.arc(stickerX, stickerY, Math.max(8, tile * 0.45), 0, Math.PI * 2);
    ctx.fillStyle = "rgba(0,0,0,0.35)";
    ctx.fill();

    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    ctx.font = `${Math.max(16, tile * 0.95)}px "Apple Color Emoji","Segoe UI Emoji","Noto Color Emoji",sans-serif`;
    ctx.fillText(emoji, stickerX, stickerY);
  }
}

function drawUnits(ctx, view) {
  const { tile } = view;
  if (!loadedUnits.length) return;

  for (const u of loadedUnits) {
    const pos = worldToScreen(u.x, u.y);
    if (pos.x + tile < -60 || pos.y + tile < -60 || pos.x > mapCanvas.width + 60 || pos.y > mapCanvas.height + 60) continue;

    const emoji = unitEmojiMap[normalizeType(u.type)] || "🛡️";
    const x = pos.x + tile * 0.65;
    const y = pos.y + tile * 0.25;
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    ctx.font = `${Math.max(14, tile * 0.72)}px "Apple Color Emoji","Segoe UI Emoji","Noto Color Emoji",sans-serif`;
    ctx.fillText(emoji, x, y);
  }
}

function drawEnemies(ctx, view) {
  const { tile } = view;
  for (const e of enemyMarkers) {
    const pos = worldToScreen(e.x, e.y);
    if (pos.x + tile < -40 || pos.y + tile < -40 || pos.x > mapCanvas.width + 40 || pos.y > mapCanvas.height + 40) continue;
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    ctx.font = `${Math.max(13, tile * 0.65)}px "Apple Color Emoji","Segoe UI Emoji","Noto Color Emoji",sans-serif`;
    ctx.fillText("👾", pos.x + tile / 2, pos.y + tile / 2);
  }
}

function render() {
  if (!dirty) {
    requestAnimationFrame(render);
    return;
  }
  mapCtx.clearRect(0, 0, mapCanvas.width, mapCanvas.height);
  const view = drawMap(mapCtx, mapCanvas);
  if (!view) {
    requestAnimationFrame(render);
    return;
  }
  drawResourceMarkers(mapCtx, view);
  drawBuildings(mapCtx, view);
  drawUnits(mapCtx, view);
  drawEnemies(mapCtx, view);
  dirty = false;
  requestAnimationFrame(render);
}

async function bootstrap() {
  mapCanvas = document.getElementById("gameCanvas");
  mapCtx = mapCanvas.getContext("2d");

  const threshold = generateElevationAndThreshold();
  assignBiomes(threshold);

  enemyMarkers = generateEnemyMarkers();
  loadUnits();

  const resize = () => {
    mapCanvas.width = window.innerWidth;
    mapCanvas.height = window.innerHeight;
    requestRender();
  };
  resize();
  window.addEventListener("resize", resize);

  bindUi();
  updateModeText();

  mapCanvas.addEventListener("mousedown", handleMouseDown);
  window.addEventListener("mousemove", handleMouseMove);
  window.addEventListener("mouseup", () => {
    isDragging = false;
  });

  mapCanvas.addEventListener("wheel", handleWheel, { passive: false });

  mapCanvas.addEventListener("touchstart", handleTouchStart, { passive: false });
  mapCanvas.addEventListener("touchmove", handleTouchMove, { passive: false });
  mapCanvas.addEventListener("touchend", handleTouchEnd, { passive: false });
  mapCanvas.addEventListener("touchcancel", handleTouchEnd, { passive: false });

  mapCanvas.addEventListener("click", async (e) => {
    if (isDragging) return;
    const rect = mapCanvas.getBoundingClientRect();
    const rel = getRelativeTileFromScreen(e.clientX - rect.left, e.clientY - rect.top);
    await onMapClick(rel.x, rel.y);
  });

  await loadGameState();
  setInterval(loadGameState, 5000);
  renderInventoryPanel();
  requestRender();
  render();
}

window.addEventListener("load", () => {
  bootstrap().catch((e) => console.error("Map bootstrap failed", e));
});

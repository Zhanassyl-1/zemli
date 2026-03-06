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
let dirty = true;
let lastCameraX = cameraX;
let lastCameraY = cameraY;
let lastScale = scale;
let needsRedraw = true;
let hoverX = null;
let hoverY = null;

const VIEW_RADIUS = 15;
const FOG_COLOR = "#0f1720";
const playerHome = { x: 0, y: 0 };
let viewCircles = [{ x: 0, y: 0, radius: VIEW_RADIUS }];

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
  gold_mine: "💰",
  stable: "🐎",
  library: "📚",
  tavern: "🍺"
};

const RESOURCE_ICONS = {
  wood: "🪵",
  stone: "🪨",
  iron: "⚔️",
  gold: "💰",
  food: "🌾"
};

const REQUIRED_RESOURCE_BY_BUILDING = {
  lumber: "wood",
  mine: "stone",
  iron_mine: "iron",
  gold: "gold",
  gold_mine: "gold"
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
  const threshold = generateElevationAndThreshold();
  assignBiomes(threshold);
  console.log("✅ Биомы сгенерированы");
}

function generateResourceNodes() {
  console.log("✅ Ресурсы будут генерироваться динамически по клеткам");
}

function requestRender() {
  dirty = true;
  needsRedraw = true;
}

function rebuildViewCircles() {
  viewCircles = [{ x: playerHome.x, y: playerHome.y, radius: VIEW_RADIUS }];
  const towers = (window.buildings || loadedBuildings || []).filter((b) => normalizeType(b.type) === "tower");
  for (const t of towers) {
    viewCircles.push({ x: Number(t.x || 0), y: Number(t.y || 0), radius: 8 });
  }
  console.log("👁️ Круги обзора:", viewCircles.length);
}

function isVisible(relX, relY) {
  for (const c of viewCircles) {
    const dx = relX - c.x;
    const dy = relY - c.y;
    if (Math.sqrt(dx * dx + dy * dy) <= c.radius) return true;
  }
  return false;
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
  const t = (raw || "").toLowerCase().trim();
  if (t === "gold_mine") return "gold";
  return t;
}

function getBiome(relX, relY) {
  const wx = relX + CENTER_X;
  const wy = relY + CENTER_Y;
  if (wx < 0 || wy < 0 || wx >= MAP_WIDTH || wy >= MAP_HEIGHT) return null;
  return biomeMap[idx(wx, wy)];
}

function resourceRandom(relX, relY, seed) {
  const wx = relX + CENTER_X;
  const wy = relY + CENTER_Y;
  return hash2(wx, wy, seed);
}

function getResourceTypeAt(relX, relY) {
  const biome = getBiome(relX, relY);
  if (biome === null || biome === BIOME.OCEAN || biome === BIOME.SHALLOW) return null;

  if (biome === BIOME.FOREST) {
    return resourceRandom(relX, relY, 901) < 0.40 ? "wood" : null;
  }

  if (biome === BIOME.MOUNTAIN) {
    const r = resourceRandom(relX, relY, 902);
    if (r < 0.15) return "iron";
    if (r < 0.45) return "stone";
    return null;
  }

  if (biome === BIOME.DESERT) {
    return resourceRandom(relX, relY, 903) < 0.08 ? "gold" : null;
  }

  if (biome === BIOME.PLAINS || biome === BIOME.JUNGLE) {
    return resourceRandom(relX, relY, 904) < 0.10 ? "food" : null;
  }

  return null;
}

function getResourcesInRadius(relX, relY, radius) {
  const result = [];
  for (let dy = -radius; dy <= radius; dy++) {
    for (let dx = -radius; dx <= radius; dx++) {
      const x = relX + dx;
      const y = relY + dy;
      const type = getResourceTypeAt(x, y);
      if (type) result.push({ x, y, type });
    }
  }
  return result;
}

function canBuildHere(relX, relY, type) {
  const normalizedType = normalizeType(type);
  const nearbyResources = getResourcesInRadius(relX, relY, 1);

  switch (normalizedType) {
    case "lumber":
      return nearbyResources.some((r) => r.type === "wood");
    case "mine":
      return nearbyResources.some((r) => r.type === "stone");
    case "iron_mine":
      return nearbyResources.some((r) => r.type === "iron");
    case "gold":
      return nearbyResources.some((r) => r.type === "gold");
    case "farm": {
      const biome = getBiome(relX, relY);
      return biome === BIOME.PLAINS || biome === BIOME.FOREST;
    }
    default:
      return true;
  }
}

function getRequiredResourceIcon(relX, relY, type) {
  const normalizedType = normalizeType(type);
  const resourceType = REQUIRED_RESOURCE_BY_BUILDING[normalizedType];
  if (!resourceType) return null;
  const nearbyResources = getResourcesInRadius(relX, relY, 1);
  const found = nearbyResources.some((r) => r.type === resourceType);
  return {
    icon: RESOURCE_ICONS[resourceType] || "❔",
    ok: found
  };
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
    rebuildViewCircles();
    return;
  }

  loadedBuildings.push({ x: 0, y: 0, type: "house", ownerId: ACTIVE_PLAYER_ID || 0, local: true });
  window.buildings = loadedBuildings;
  playerHome.x = 0;
  playerHome.y = 0;
  console.log("🏠 Добавлен стартовый дом: (0,0)");
  rebuildViewCircles();
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

function updateResourcesUI(res) {
  const safe = res || {};
  document.getElementById("wood").textContent = Number(safe.wood || 0);
  document.getElementById("stone").textContent = Number(safe.stone || 0);
  document.getElementById("iron").textContent = Number(safe.iron || 0);
  document.getElementById("gold").textContent = Number(safe.gold || 0);
  document.getElementById("food").textContent = Number(safe.food || 0);
  document.getElementById("population").textContent = Number(safe.population || 0);
  document.getElementById("maxPop").textContent = Number(safe.maxPopulation || 0);
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
    updateResourcesUI(gameResources);
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

const buildingPriceMap = {
  lumber: "100🪵",
  mine: "150🪨",
  iron_mine: "200⚔️+100🪨",
  farm: "50🌾+50🪵",
  tower: "200🪵+150🪨",
  warehouse: "300🪵+200🪨",
  house: "100🪵+50🪨",
  barracks: "400🪵+300⚔️"
};

function getPrice(type) {
  return buildingPriceMap[normalizeType(type)] || "—";
}

function checkCanPlace(relX, relY, type) {
  if (!isVisible(relX, relY)) return false;
  const occupied = (window.buildings || loadedBuildings || []).some((b) => Number(b.x) === relX && Number(b.y) === relY);
  if (occupied) return false;
  const wx = relX + CENTER_X;
  const wy = relY + CENTER_Y;
  if (wx < 0 || wy < 0 || wx >= MAP_WIDTH || wy >= MAP_HEIGHT) return false;
  const biome = biomeMap[idx(wx, wy)];
  if (biome === BIOME.OCEAN || biome === BIOME.SHALLOW) return false;
  if (!type) return false;
  return canBuildHere(relX, relY, type);
}

function drawHover(ctx) {
  if (!selectedBuilding || hoverX === null || hoverY === null) return;
  const x = ((hoverX + CENTER_X) * TILE_SIZE * scale) - cameraX;
  const y = ((hoverY + CENTER_Y) * TILE_SIZE * scale) - cameraY;
  const tile = TILE_SIZE * scale;
  const canPlace = checkCanPlace(hoverX, hoverY, selectedBuilding);
  ctx.strokeStyle = canPlace ? "#00ff00" : "#ff0000";
  ctx.lineWidth = 3;
  ctx.strokeRect(x, y, tile, tile);
  ctx.fillStyle = "#ffffff";
  ctx.font = '12px "Courier New"';
  ctx.fillText(getPrice(selectedBuilding), x, y - 6);
  const required = getRequiredResourceIcon(hoverX, hoverY, selectedBuilding);
  if (required) {
    ctx.font = `${Math.max(14, tile * 0.5)}px "Apple Color Emoji","Segoe UI Emoji","Noto Color Emoji",sans-serif`;
    ctx.fillText(required.icon, x + tile + 10, y + 12);
  }
}

async function onMapClick(relX, relY) {
  updateResourcesUI(gameResources);
  if (!isVisible(relX, relY)) {
    console.log("🚫 Клетка вне обзора:", relX, relY);
    return;
  }
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

    const canPlace = checkCanPlace(relX, relY, selectedBuilding);
    if (!canPlace) {
      console.log("🚫 Нельзя поставить", selectedBuilding, "в", relX, relY);
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
      updateResourcesUI(gameResources);
      requestRender();
    }
    return;
  }

  if (!actionMode) return;

  if (actionMode === "move") {
    console.log(`🚚 Переместить в (${relX}, ${relY})`);
    if (selectedUnitType && armyOrderMode === "recruit") {
      addUnit(selectedUnitType, relX, relY);
      await loadGameState();
      requestRender();
      return;
    }
    if (armyOrderMode === "move") {
      const unit = pickNearestUnit(relX, relY);
      if (unit) {
        await moveUnit(unit.id, relX, relY);
        await loadGameState();
        requestRender();
      }
    }
    return;
  }

  if (actionMode === "attack") {
    await attack(relX, relY);
    await loadGameState();
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
  hoverX = rel.x;
  hoverY = rel.y;
  if (selectedBuilding) requestRender();

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
      if (!isVisible(relX, relY)) {
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
  const { tile, startCol, startRow, endCol, endRow } = view;
  ctx.textAlign = "center";
  ctx.textBaseline = "middle";
  ctx.font = `${Math.max(11, tile * 0.45)}px "Apple Color Emoji","Segoe UI Emoji","Noto Color Emoji",sans-serif`;

  for (let row = startRow; row < endRow; row++) {
    for (let col = startCol; col < endCol; col++) {
      const relX = col - CENTER_X;
      const relY = row - CENTER_Y;
      if (!isVisible(relX, relY)) continue;
      const resourceType = getResourceTypeAt(relX, relY);
      if (!resourceType) continue;
      const icon = RESOURCE_ICONS[resourceType];
      if (!icon) continue;
      const x = (col * tile) - cameraX;
      const y = (row * tile) - cameraY;
      ctx.fillText(icon, x + tile / 2, y + tile / 2);
    }
  }
}

function drawBuildHints(ctx, view) {
  if (!selectedBuilding) return;
  const { tile, startCol, startRow, endCol, endRow } = view;
  ctx.lineWidth = Math.max(1, tile * 0.08);

  for (let row = startRow; row < endRow; row++) {
    for (let col = startCol; col < endCol; col++) {
      const relX = col - CENTER_X;
      const relY = row - CENTER_Y;
      if (!isVisible(relX, relY)) continue;
      const canPlace = checkCanPlace(relX, relY, selectedBuilding);
      const x = (col * tile) - cameraX;
      const y = (row * tile) - cameraY;
      ctx.strokeStyle = canPlace ? "rgba(70, 220, 90, 0.55)" : "rgba(240, 60, 60, 0.45)";
      ctx.strokeRect(x + 1, y + 1, tile - 2, tile - 2);

      const required = getRequiredResourceIcon(relX, relY, selectedBuilding);
      if (required) {
        ctx.textAlign = "left";
        ctx.textBaseline = "top";
        ctx.font = `${Math.max(9, tile * 0.3)}px "Apple Color Emoji","Segoe UI Emoji","Noto Color Emoji",sans-serif`;
        ctx.fillStyle = required.ok ? "#d5ffd5" : "#ffd5d5";
        ctx.fillText(required.icon, x + 2, y + 2);
      }
    }
  }
}

function drawBuildings(ctx, view) {
  if (Array.isArray(ctx)) {
    loadedBuildings = ctx;
    window.buildings = loadedBuildings;
    ensureStartingHome();
    rebuildViewCircles();
    requestRender();
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
  if (cameraX !== lastCameraX || cameraY !== lastCameraY || scale !== lastScale) {
    needsRedraw = true;
    dirty = true;
    lastCameraX = cameraX;
    lastCameraY = cameraY;
    lastScale = scale;
  }

  if (!dirty && !needsRedraw) {
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
  drawBuildHints(mapCtx, view);
  drawHover(mapCtx);
  dirty = false;
  needsRedraw = false;
  requestAnimationFrame(render);
}

async function bootstrap() {
  mapCanvas = document.getElementById("gameCanvas");
  mapCtx = mapCanvas.getContext("2d");

  generateBiomeMap();
  generateResourceNodes();

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
    const mouseX = e.clientX - rect.left;
    const mouseY = e.clientY - rect.top;
    const worldX = Math.round((mouseX + cameraX) / (TILE_SIZE * scale));
    const worldY = Math.round((mouseY + cameraY) / (TILE_SIZE * scale));
    const tileX = worldX - CENTER_X;
    const tileY = worldY - CENTER_Y;
    console.log(`Клик по клетке (${tileX}, ${tileY})`);
    await onMapClick(tileX, tileY);
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

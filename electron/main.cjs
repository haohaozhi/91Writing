const {
  app,
  BrowserWindow,
  dialog,
  ipcMain,
  safeStorage,
  shell,
} = require("electron");
const path = require("path");
const fs = require("fs");
const { spawn } = require("child_process");

const BACKEND_PORT = process.env.DREAM_WRITER_BACKEND_PORT || "17691";
const BACKEND_URL = `http://127.0.0.1:${BACKEND_PORT}`;
const VITE_DEV_URL =
  process.env.DREAM_WRITER_DEV_URL || "http://127.0.0.1:7520";
const DEBUG_MODE = ["1", "true", "yes"].includes(
  String(process.env.DREAM_WRITER_DEBUG || "").toLowerCase(),
);

let mainWindow;
let backendProcess;
let secureStorageUnavailableWarningShown = false;

function isDev() {
  return !app.isPackaged;
}

function shouldDebug() {
  return isDev() || DEBUG_MODE;
}

function backendJarPath() {
  if (isDev()) {
    return path.join(
      app.getAppPath(),
      "backend",
      "target",
      "dream-writer-backend-0.1.0-SNAPSHOT.jar",
    );
  }
  return path.join(
    process.resourcesPath,
    "backend",
    "dream-writer-backend.jar",
  );
}

function bundledJavaPath() {
  const executable = process.platform === "win32" ? "java.exe" : "java";
  const candidate = path.join(
    process.resourcesPath || "",
    "runtime",
    "bin",
    executable,
  );
  return fs.existsSync(candidate) ? candidate : "java";
}

function defaultProjectsPath() {
  if (process.env.DREAM_WRITER_PROJECTS_PATH) {
    return path.resolve(process.env.DREAM_WRITER_PROJECTS_PATH);
  }
  if (isDev()) {
    return path.join(app.getAppPath(), "docs");
  }
  return path.join(app.getPath("documents"), "DreamWriter");
}

function secureStorePath() {
  return path.join(app.getPath("userData"), "secure-api-keys.json");
}

function apiConfigStorePath() {
  return path.join(app.getPath("userData"), "api-configs.json");
}

function appStateStorePath() {
  return path.join(app.getPath("userData"), "app-state.json");
}

function projectRegistryStorePath() {
  return path.join(app.getPath("userData"), "project-registry.json");
}

function readSecureStore() {
  const file = secureStorePath();
  if (!fs.existsSync(file)) {
    return {};
  }

  try {
    return JSON.parse(fs.readFileSync(file, "utf8"));
  } catch (error) {
    console.warn(`[DreamWriter] Failed to read secure store: ${error.message}`);
    return {};
  }
}

function readApiConfigStore() {
  const file = apiConfigStorePath();
  if (!fs.existsSync(file)) {
    return {};
  }

  try {
    return JSON.parse(fs.readFileSync(file, "utf8"));
  } catch (error) {
    console.warn(
      `[DreamWriter] Failed to read api config store: ${error.message}`,
    );
    return {};
  }
}

function writeSecureStore(store) {
  const file = secureStorePath();
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(store, null, 2), "utf8");
}

function writeApiConfigStore(store) {
  const file = apiConfigStorePath();
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(store, null, 2), "utf8");
}

function readAppStateStore() {
  const file = appStateStorePath();
  if (!fs.existsSync(file)) {
    return {};
  }

  try {
    return JSON.parse(fs.readFileSync(file, "utf8"));
  } catch (error) {
    console.warn(
      `[DreamWriter] Failed to read app state store: ${error.message}`,
    );
    return {};
  }
}

function writeAppStateStore(store) {
  const file = appStateStorePath();
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(store, null, 2), "utf8");
}

function normalizeProjectRegistryStore(store) {
  const value = store && typeof store === "object" ? store : {};
  const projects =
    value.projects && typeof value.projects === "object" ? value.projects : {};
  const legacyBindings =
    value.legacyBindings && typeof value.legacyBindings === "object"
      ? value.legacyBindings
      : {};

  return {
    projects,
    legacyBindings,
  };
}

function readProjectRegistryStore() {
  const file = projectRegistryStorePath();
  if (!fs.existsSync(file)) {
    return normalizeProjectRegistryStore({});
  }

  try {
    return normalizeProjectRegistryStore(
      JSON.parse(fs.readFileSync(file, "utf8")),
    );
  } catch (error) {
    console.warn(
      `[DreamWriter] Failed to read project registry store: ${error.message}`,
    );
    return normalizeProjectRegistryStore({});
  }
}

function writeProjectRegistryStore(store) {
  const file = projectRegistryStorePath();
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(
    file,
    JSON.stringify(normalizeProjectRegistryStore(store), null, 2),
    "utf8",
  );
}

function encryptSecret(value) {
  if (!safeStorage.isEncryptionAvailable()) {
    throw new Error("当前系统安全存储不可用");
  }
  return safeStorage.encryptString(value).toString("base64");
}

function decryptSecret(value) {
  if (!value) {
    return "";
  }
  if (!safeStorage.isEncryptionAvailable()) {
    throw new Error("当前系统安全存储不可用");
  }
  return safeStorage.decryptString(Buffer.from(value, "base64"));
}

function warnSecureStorageUnavailable(message) {
  if (secureStorageUnavailableWarningShown) {
    return;
  }
  secureStorageUnavailableWarningShown = true;
  console.warn(message);
}

async function waitForBackend(timeoutMs = 15000) {
  const startedAt = Date.now();
  while (Date.now() - startedAt < timeoutMs) {
    try {
      const response = await fetch(`${BACKEND_URL}/actuator/health`);
      if (response.ok) {
        return true;
      }
    } catch {
      // Backend is still starting.
    }
    await new Promise((resolve) => setTimeout(resolve, 300));
  }
  return false;
}

async function startBackend() {
  const jar = backendJarPath();
  if (!fs.existsSync(jar)) {
    if (isDev()) {
      console.warn(`[DreamWriter] Backend jar not found: ${jar}`);
      return false;
    }
    await dialog.showErrorBox("DreamWriter 后端缺失", `未找到后端文件：${jar}`);
    return false;
  }

  backendProcess = spawn(
    bundledJavaPath(),
    [
      "-jar",
      jar,
      `--server.port=${BACKEND_PORT}`,
      "--server.address=127.0.0.1",
      `--dream-writer.request-log-enabled=${shouldDebug()}`,
    ],
    {
      stdio: shouldDebug() ? "inherit" : "ignore",
      env: {
        ...process.env,
        DREAM_WRITER_BACKEND_PORT: BACKEND_PORT,
        DREAM_WRITER_USER_DATA_PATH: app.getPath("userData"),
        DREAM_WRITER_PROJECT_REGISTRY_PATH: projectRegistryStorePath(),
      },
    },
  );

  backendProcess.on("exit", (code, signal) => {
    if (shouldDebug()) {
      console.log(
        `[DreamWriter] Backend exited: code=${code}, signal=${signal}`,
      );
    }
    backendProcess = null;
  });

  return waitForBackend();
}

async function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 820,
    minWidth: 960,
    minHeight: 640,
    title: "梦想家写作",
    webPreferences: {
      preload: path.join(__dirname, "preload.cjs"),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });

  mainWindow.webContents.on("before-input-event", (event, input) => {
    if (input.type !== "keyDown") {
      return;
    }

    const isF12 = input.key === "F12";
    const isDevToolsShortcut =
      input.key.toLowerCase() === "i" &&
      input.alt &&
      (input.meta || input.control);
    if (isF12 || isDevToolsShortcut) {
      event.preventDefault();
      toggleDevTools();
    }
  });

  if (isDev()) {
    await mainWindow.loadURL(VITE_DEV_URL);
  } else {
    await mainWindow.loadFile(
      path.join(app.getAppPath(), "dist", "index.html"),
    );
  }

  if (shouldDebug()) {
    mainWindow.webContents.openDevTools({ mode: "detach" });
  }
}

function toggleDevTools() {
  if (!mainWindow) {
    return;
  }

  if (mainWindow.webContents.isDevToolsOpened()) {
    mainWindow.webContents.closeDevTools();
  } else {
    mainWindow.webContents.openDevTools({ mode: "detach" });
  }
}

ipcMain.handle("dream-writer:select-directory", async (event, options = {}) => {
  const owner = BrowserWindow.fromWebContents(event.sender);
  const result = await dialog.showOpenDialog(owner, {
    title: options.title || "选择目录",
    defaultPath: options.defaultPath || undefined,
    properties: ["openDirectory", "createDirectory"],
  });

  if (result.canceled) {
    return null;
  }

  return result.filePaths[0] || null;
});

ipcMain.handle("dream-writer:toggle-devtools", () => {
  toggleDevTools();
});

ipcMain.handle("dream-writer:open-path", async (_event, targetPath) => {
  const normalized = path.resolve(String(targetPath || ""));
  if (!normalized) {
    throw new Error("缺少要打开的路径");
  }
  return shell.openPath(normalized);
});

ipcMain.handle("dream-writer:get-api-key", (_event, key) => {
  const store = readSecureStore();
  try {
    return decryptSecret(store[String(key || "")]);
  } catch (error) {
    warnSecureStorageUnavailable(
      `[DreamWriter] Failed to read API key: ${error.message}`,
    );
    return "";
  }
});

ipcMain.handle("dream-writer:set-api-key", (_event, key, value) => {
  const name = String(key || "").trim();
  if (!name) {
    throw new Error("缺少 API key 名称");
  }

  const store = readSecureStore();
  if (value == null || String(value).trim() === "") {
    delete store[name];
  } else if (!safeStorage.isEncryptionAvailable()) {
    warnSecureStorageUnavailable(
      "[DreamWriter] Secure storage is unavailable; renderer will use the user-approved plaintext API key fallback.",
    );
    return false;
  } else {
    store[name] = encryptSecret(String(value));
  }
  writeSecureStore(store);
  return true;
});

ipcMain.handle("dream-writer:delete-api-key", (_event, key) => {
  const store = readSecureStore();
  delete store[String(key || "")];
  writeSecureStore(store);
  return true;
});

ipcMain.handle("dream-writer:get-secure-store-info", () => {
  return {
    path: secureStorePath(),
    encryptionAvailable: safeStorage.isEncryptionAvailable(),
  };
});

ipcMain.handle("dream-writer:get-api-config", (_event, key) => {
  const store = readApiConfigStore();
  return store[String(key || "")] || null;
});

ipcMain.handle("dream-writer:set-api-config", (_event, key, value) => {
  const name = String(key || "").trim();
  if (!name) {
    throw new Error("缺少 API 配置名称");
  }

  const store = readApiConfigStore();
  if (!value || typeof value !== "object") {
    delete store[name];
  } else {
    store[name] = value;
  }
  writeApiConfigStore(store);
  return true;
});

ipcMain.handle("dream-writer:delete-api-config", (_event, key) => {
  const store = readApiConfigStore();
  delete store[String(key || "")];
  writeApiConfigStore(store);
  return true;
});

ipcMain.handle("dream-writer:get-app-state-all", () => {
  return readAppStateStore();
});

ipcMain.handle("dream-writer:get-app-state", (_event, key) => {
  const store = readAppStateStore();
  return store[String(key || "")];
});

ipcMain.handle("dream-writer:set-app-state", (_event, key, value) => {
  const name = String(key || "").trim();
  if (!name) {
    throw new Error("缺少应用状态名称");
  }

  const store = readAppStateStore();
  store[name] = value;
  writeAppStateStore(store);
  return true;
});

ipcMain.handle("dream-writer:delete-app-state", (_event, key) => {
  const store = readAppStateStore();
  delete store[String(key || "")];
  writeAppStateStore(store);
  return true;
});

ipcMain.handle("dream-writer:replace-app-state", (_event, value) => {
  const store = value && typeof value === "object" ? value : {};
  writeAppStateStore(store);
  return true;
});

ipcMain.handle("dream-writer:get-project-registry-all", () => {
  return readProjectRegistryStore();
});

ipcMain.handle("dream-writer:replace-project-registry", (_event, value) => {
  writeProjectRegistryStore(value);
  return true;
});

ipcMain.handle("dream-writer:get-app-paths", () => {
  return {
    defaultProjectsPath: defaultProjectsPath(),
    userDataPath: app.getPath("userData"),
    bundledJavaPath: bundledJavaPath(),
    backendJarPath: backendJarPath(),
    apiConfigStorePath: apiConfigStorePath(),
    secureStorePath: secureStorePath(),
    appStateStorePath: appStateStorePath(),
    projectRegistryStorePath: projectRegistryStorePath(),
  };
});

function stopBackend() {
  if (backendProcess) {
    backendProcess.kill();
    backendProcess = null;
  }
}

app.whenReady().then(async () => {
  const backendReady = await startBackend();
  if (!backendReady && isDev()) {
    console.warn("[DreamWriter] Continuing without backend in dev mode.");
  }
  await createWindow();
});

app.on("activate", async () => {
  if (BrowserWindow.getAllWindows().length === 0) {
    await createWindow();
  }
});

app.on("before-quit", stopBackend);

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") {
    app.quit();
  }
});

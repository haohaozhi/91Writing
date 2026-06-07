const { contextBridge, ipcRenderer } = require("electron");

const backendPort = process.env.DREAM_WRITER_BACKEND_PORT || "17691";

contextBridge.exposeInMainWorld("dreamWriter", {
  backendUrl: `http://127.0.0.1:${backendPort}`,
  selectDirectory: (options) =>
    ipcRenderer.invoke("dream-writer:select-directory", options || {}),
  openPath: (targetPath) =>
    ipcRenderer.invoke("dream-writer:open-path", targetPath),
  toggleDevTools: () => ipcRenderer.invoke("dream-writer:toggle-devtools"),
  apiSecrets: {
    get: (key) => ipcRenderer.invoke("dream-writer:get-api-key", key),
    set: (key, value) =>
      ipcRenderer.invoke("dream-writer:set-api-key", key, value),
    delete: (key) => ipcRenderer.invoke("dream-writer:delete-api-key", key),
    info: () => ipcRenderer.invoke("dream-writer:get-secure-store-info"),
  },
  apiConfigs: {
    get: (key) => ipcRenderer.invoke("dream-writer:get-api-config", key),
    set: (key, value) =>
      ipcRenderer.invoke("dream-writer:set-api-config", key, value),
    delete: (key) => ipcRenderer.invoke("dream-writer:delete-api-config", key),
  },
  appState: {
    all: () => ipcRenderer.invoke("dream-writer:get-app-state-all"),
    get: (key) => ipcRenderer.invoke("dream-writer:get-app-state", key),
    set: (key, value) =>
      ipcRenderer.invoke("dream-writer:set-app-state", key, value),
    delete: (key) => ipcRenderer.invoke("dream-writer:delete-app-state", key),
    replace: (value) =>
      ipcRenderer.invoke("dream-writer:replace-app-state", value || {}),
  },
  projectRegistry: {
    all: () => ipcRenderer.invoke("dream-writer:get-project-registry-all"),
    replace: (value) =>
      ipcRenderer.invoke("dream-writer:replace-project-registry", value || {}),
  },
  paths: {
    info: () => ipcRenderer.invoke("dream-writer:get-app-paths"),
  },
});

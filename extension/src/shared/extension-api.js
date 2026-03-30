const runtimeApi = globalThis.browser || globalThis.chrome;

function callbackBridge(invoker) {
  return new Promise((resolve, reject) => {
    invoker((result) => {
      const lastError = globalThis.chrome?.runtime?.lastError;
      if (lastError) {
        reject(new Error(lastError.message));
        return;
      }

      resolve(result);
    });
  });
}

function resolveStorageArea(area = "sync") {
  const storageArea = runtimeApi.storage?.[area];

  if (!storageArea) {
    throw new Error(`Browser storage area "${area}" is not available.`);
  }

  return storageArea;
}

export const extensionApi = {
  runtime: runtimeApi.runtime,
  tabs: runtimeApi.tabs,
  storage: runtimeApi.storage,

  async sendMessage(message) {
    if (globalThis.browser) {
      return runtimeApi.runtime.sendMessage(message);
    }

    return callbackBridge((done) => runtimeApi.runtime.sendMessage(message, done));
  },

  async queryTabs(queryInfo) {
    if (globalThis.browser) {
      return runtimeApi.tabs.query(queryInfo);
    }

    return callbackBridge((done) => runtimeApi.tabs.query(queryInfo, done));
  },

  async storageGet(keys, area = "sync") {
    const storageArea = resolveStorageArea(area);

    if (globalThis.browser) {
      return storageArea.get(keys);
    }

    return callbackBridge((done) => storageArea.get(keys, done));
  },

  async storageSet(value, area = "sync") {
    const storageArea = resolveStorageArea(area);

    if (globalThis.browser) {
      return storageArea.set(value);
    }

    return callbackBridge((done) => storageArea.set(value, done));
  },

  async storageRemove(keys, area = "sync") {
    const storageArea = resolveStorageArea(area);

    if (globalThis.browser) {
      return storageArea.remove(keys);
    }

    return callbackBridge((done) => storageArea.remove(keys, done));
  },

  async storageSetAccessLevel(area, accessLevel) {
    const storageArea = resolveStorageArea(area);
    const setter = storageArea.setAccessLevel;

    if (typeof setter !== "function") {
      return;
    }

    if (globalThis.browser) {
      return setter.call(storageArea, { accessLevel });
    }

    return callbackBridge((done) => setter.call(storageArea, { accessLevel }, done));
  },

  async openOptionsPage() {
    if (globalThis.browser) {
      return runtimeApi.runtime.openOptionsPage();
    }

    return callbackBridge((done) => runtimeApi.runtime.openOptionsPage(done));
  }
};

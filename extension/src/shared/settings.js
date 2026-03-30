import { extensionApi } from "./extension-api.js";

const PUBLIC_DEFAULTS = {
  apiBaseUrl: "http://localhost:9002",
  defaultCollectionId: "",
  defaultTags: []
};

const TOKEN_STORAGE_KEY = "accessToken";
const PRIVATE_STORAGE_AREA = "session";

function normalizeTags(value) {
  if (Array.isArray(value)) {
    return value.map((tag) => String(tag).trim()).filter(Boolean);
  }

  if (typeof value === "string") {
    return value
      .split(",")
      .map((tag) => tag.trim())
      .filter(Boolean);
  }

  return [];
}

export function normalizeSettings(raw = {}) {
  return {
    apiBaseUrl: String(raw.apiBaseUrl || PUBLIC_DEFAULTS.apiBaseUrl).trim().replace(/\/+$/, ""),
    accessToken: String(raw.accessToken || "").trim(),
    defaultCollectionId: String(raw.defaultCollectionId || PUBLIC_DEFAULTS.defaultCollectionId).trim(),
    defaultTags: normalizeTags(raw.defaultTags)
  };
}

async function loadAccessToken() {
  try {
    await extensionApi.storageSetAccessLevel(PRIVATE_STORAGE_AREA, "TRUSTED_CONTEXTS");
    const stored = await extensionApi.storageGet({ [TOKEN_STORAGE_KEY]: "" }, PRIVATE_STORAGE_AREA);
    return String(stored?.[TOKEN_STORAGE_KEY] || "").trim();
  } catch {
    return "";
  }
}

async function saveAccessToken(value) {
  const token = String(value || "").trim();

  try {
    await extensionApi.storageSetAccessLevel(PRIVATE_STORAGE_AREA, "TRUSTED_CONTEXTS");

    if (token) {
      await extensionApi.storageSet({ [TOKEN_STORAGE_KEY]: token }, PRIVATE_STORAGE_AREA);
    } else {
      await extensionApi.storageRemove(TOKEN_STORAGE_KEY, PRIVATE_STORAGE_AREA);
    }

    return;
  } catch {
    throw new Error("This browser cannot keep the Markko access token in session-only extension storage.");
  }
}

export async function loadSettings() {
  const [stored, accessToken] = await Promise.all([
    extensionApi.storageGet(PUBLIC_DEFAULTS),
    loadAccessToken()
  ]);

  return normalizeSettings({ ...stored, accessToken });
}

export async function saveSettings(nextValue) {
  const normalized = normalizeSettings(nextValue);
  const { accessToken, ...publicSettings } = normalized;

  await Promise.all([
    extensionApi.storageSet(publicSettings),
    saveAccessToken(accessToken)
  ]);

  return normalized;
}

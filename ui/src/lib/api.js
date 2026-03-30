const DEFAULT_API_PORT = "9002";
const MINIO_HINT = `Request reached MinIO instead of the Markko API. Set API base URL to http://localhost:${DEFAULT_API_PORT}.`;

function normalizeBaseUrl(baseUrl) {
  return (baseUrl || "").trim().replace(/\/+$/, "");
}

export function resolveDefaultApiBaseUrl() {
  const configured = normalizeBaseUrl(import.meta.env.VITE_API_BASE_URL);
  if (configured) {
    return configured;
  }

  if (typeof window !== "undefined") {
    const protocol = window.location.protocol === "https:" ? "https:" : "http:";
    return `${protocol}//${window.location.hostname}:${DEFAULT_API_PORT}`;
  }

  return `http://localhost:${DEFAULT_API_PORT}`;
}

async function parseResponse(response) {
  const raw = await response.text();

  if (!raw) {
    return null;
  }

  try {
    return JSON.parse(raw);
  } catch {
    return raw;
  }
}

function messageFromError(path, response, payload) {
  if (typeof payload === "object" && payload && "error" in payload) {
    return payload.error;
  }

  const server = (response.headers.get("server") || "").toLowerCase();
  const contentType = (response.headers.get("content-type") || "").toLowerCase();

  if (server.includes("minio") || contentType.includes("application/xml")) {
    return MINIO_HINT;
  }

  if (response.status === 0 || !response.statusText) {
    return `Request to ${path} failed.`;
  }

  return `${response.status} ${response.statusText}`;
}

export function createMarkkoClient(getBaseUrl, getToken) {
  async function request(path, options = {}) {
    const headers = new Headers(options.headers || {});
    headers.set("Accept", "application/json");

    if (options.json !== undefined) {
      headers.set("Content-Type", "application/json");
    }

    const token = getToken?.();
    if (token) {
      headers.set("Authorization", `Bearer ${token}`);
    }

    const response = await fetch(`${normalizeBaseUrl(getBaseUrl())}${path}`, {
      method: options.method || "GET",
      headers,
      body: options.json !== undefined ? JSON.stringify(options.json) : options.body
    });

    const payload = await parseResponse(response);

    if (!response.ok) {
      const error = new Error(messageFromError(path, response, payload));
      error.status = response.status;
      error.payload = payload;
      throw error;
    }

    return payload;
  }

  return {
    register(payload) {
      return request("/auth/register", { method: "POST", json: payload });
    },
    login(credentials) {
      return request("/auth/login", { method: "POST", json: credentials });
    },
    me() {
      return request("/auth/me");
    },
    listLinks() {
      return request("/links");
    },
    getLink(id) {
      return request(`/links/${id}`);
    },
    createLink(link) {
      return request("/links", { method: "POST", json: link });
    },
    searchLinks(query) {
      return request(`/links/search?q=${encodeURIComponent(query)}`);
    },
    listCollections() {
      return request("/collections");
    },
    createCollection(name) {
      return request("/collections", { method: "POST", json: { name } });
    },
    exportStatus(linkId) {
      return request(`/export/${linkId}/status`);
    },
    reexport(linkId) {
      return request(`/export/${linkId}/reexport`, { method: "POST" });
    },
    deleteExport(linkId) {
      return request(`/export/${linkId}`, { method: "DELETE" });
    },
    pickVaultDirectory() {
      return request("/vault/pick-directory", { method: "POST" });
    },
    listVaultDirectories() {
      return request("/vault/directories");
    }
  };
}

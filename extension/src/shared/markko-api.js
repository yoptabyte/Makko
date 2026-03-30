function buildHeaders(token) {
  return {
    Accept: "application/json",
    Authorization: `Bearer ${token}`,
    "Content-Type": "application/json"
  };
}

function errorMessage(response, result) {
  if (typeof result === "object" && result && "error" in result) {
    return result.error;
  }

  const server = (response.headers.get("server") || "").toLowerCase();
  const contentType = (response.headers.get("content-type") || "").toLowerCase();

  if (server.includes("minio") || contentType.includes("application/xml")) {
    return "Request reached MinIO instead of the Markko API. Use http://localhost:9002 as API base URL.";
  }

  return `${response.status} ${response.statusText}`;
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

export async function savePage(settings, payload) {
  if (!settings.apiBaseUrl) {
    throw new Error("Missing Markko API base URL.");
  }

  if (!settings.accessToken) {
    throw new Error("Configure a Markko access token first.");
  }

  const requestBody = {
    url: payload.url,
    tags: payload.tags?.length ? payload.tags : settings.defaultTags
  };

  const collectionId = payload.collectionId || settings.defaultCollectionId;
  if (collectionId) {
    requestBody.collectionId = Number(collectionId);
  }

  const response = await fetch(`${settings.apiBaseUrl}/links`, {
    method: "POST",
    headers: buildHeaders(settings.accessToken),
    body: JSON.stringify(requestBody)
  });

  const result = await parseResponse(response);

  if (response.status === 409) {
    return {
      ok: false,
      conflict: true,
      message: "Link already exists in Markko.",
      payload: result
    };
  }

  if (!response.ok) {
    throw new Error(errorMessage(response, result));
  }

  return {
    ok: true,
    message: "Link saved and queued for parsing.",
    payload: result
  };
}

export async function fetchViewer(settings) {
  if (!settings.apiBaseUrl || !settings.accessToken) {
    throw new Error("Configure API URL and access token first.");
  }

  const response = await fetch(`${settings.apiBaseUrl}/auth/me`, {
    headers: {
      Accept: "application/json",
      Authorization: `Bearer ${settings.accessToken}`
    }
  });

  const result = await parseResponse(response);

  if (!response.ok) {
    throw new Error(errorMessage(response, result));
  }

  return result;
}

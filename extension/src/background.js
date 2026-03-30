import { savePage } from "./shared/markko-api.js";
import { loadSettings } from "./shared/settings.js";

const runtime = globalThis.browser || globalThis.chrome;

async function handleMessage(message, sender) {
  switch (message?.type) {
    case "get-settings": {
      const settings = await loadSettings();
      return {
        apiBaseUrl: settings.apiBaseUrl,
        defaultCollectionId: settings.defaultCollectionId,
        defaultTags: settings.defaultTags,
        hasAccessToken: Boolean(settings.accessToken)
      };
    }

    case "save-page": {
      const settings = await loadSettings();
      return savePage(settings, {
        url: message.url || sender?.tab?.url,
        title: message.title || sender?.tab?.title,
        tags: message.tags,
        collectionId: message.collectionId
      });
    }

    default:
      return { ok: true };
  }
}

runtime.runtime.onMessage.addListener((message, sender, sendResponse) => {
  handleMessage(message, sender)
    .then((result) => sendResponse(result))
    .catch((error) => sendResponse({ ok: false, message: error.message, error: error.message }));

  return true;
});

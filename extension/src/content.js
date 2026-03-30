if (!window.__markkoSaveButtonMounted) {
  window.__markkoSaveButtonMounted = true;

  const root = document.createElement("div");
  root.id = "markko-save-root";
  root.innerHTML = `
    <button id="markko-save-button" type="button">Save to Markko</button>
    <div id="markko-save-toast" aria-live="polite"></div>
  `;

  const style = document.createElement("style");
  style.textContent = `
    #markko-save-root {
      position: fixed;
      top: 18px;
      right: 18px;
      z-index: 2147483647;
      display: grid;
      gap: 8px;
      justify-items: end;
      font-family: "Avenir Next", "Segoe UI Variable Text", sans-serif;
    }

    #markko-save-button {
      border: 0;
      border-radius: 999px;
      padding: 12px 18px;
      background: linear-gradient(135deg, #0f5132, #1d7a55);
      color: #fff;
      font-size: 14px;
      font-weight: 700;
      box-shadow: 0 18px 34px rgba(15, 81, 50, 0.28);
      cursor: pointer;
    }

    #markko-save-button[data-busy="true"] {
      opacity: 0.75;
      cursor: wait;
    }

    #markko-save-toast {
      min-width: 180px;
      max-width: 280px;
      padding: 10px 12px;
      border-radius: 16px;
      background: rgba(24, 21, 16, 0.82);
      color: #fff9f0;
      font-size: 13px;
      line-height: 1.35;
      opacity: 0;
      transform: translateY(-6px);
      transition:
        opacity 180ms ease,
        transform 180ms ease;
      pointer-events: none;
    }

    #markko-save-toast[data-visible="true"] {
      opacity: 1;
      transform: translateY(0);
    }
  `;

  document.documentElement.append(style);
  document.documentElement.append(root);

  const button = root.querySelector("#markko-save-button");
  const toast = root.querySelector("#markko-save-toast");
  let toastTimer = null;

  async function sendMessage(message) {
    if (globalThis.browser) {
      return globalThis.browser.runtime.sendMessage(message);
    }

    return new Promise((resolve, reject) => {
      globalThis.chrome.runtime.sendMessage(message, (response) => {
        const lastError = globalThis.chrome?.runtime?.lastError;
        if (lastError) {
          reject(new Error(lastError.message));
          return;
        }

        resolve(response);
      });
    });
  }

  function flash(message) {
    toast.textContent = message;
    toast.dataset.visible = "true";

    window.clearTimeout(toastTimer);
    toastTimer = window.setTimeout(() => {
      toast.dataset.visible = "false";
    }, 2600);
  }

  button.addEventListener("click", async () => {
    button.dataset.busy = "true";
    button.textContent = "Saving...";

    try {
      const result = await sendMessage({
        type: "save-page",
        url: window.location.href,
        title: document.title,
        tags: []
      });

      if (result?.ok === false && result.error) {
        flash(result.error);
      } else {
        flash(result?.message || "Saved to Markko.");
      }
    } catch (error) {
      flash(error.message);
    } finally {
      button.dataset.busy = "false";
      button.textContent = "Save to Markko";
    }
  });
}

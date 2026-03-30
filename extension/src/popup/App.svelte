<script>
  import { onMount } from "svelte";
  import { extensionApi } from "../shared/extension-api.js";

  let activeTab = null;
  let tags = "";
  let collectionId = "";
  let settings = null;
  let busy = false;
  let statusMessage = "";
  let errorMessage = "";

  onMount(async () => {
    settings = await extensionApi.sendMessage({ type: "get-settings" });
    collectionId = settings.defaultCollectionId || "";

    const [tab] = await extensionApi.queryTabs({
      active: true,
      currentWindow: true
    });

    activeTab = tab || null;
  });

  function parseTags(raw) {
    return raw
      .split(",")
      .map((entry) => entry.trim())
      .filter(Boolean);
  }

  async function saveCurrentPage() {
    busy = true;
    statusMessage = "";
    errorMessage = "";

    try {
      const result = await extensionApi.sendMessage({
        type: "save-page",
        url: activeTab?.url,
        title: activeTab?.title,
        tags: parseTags(tags),
        collectionId
      });

      if (result?.ok === false && result.error) {
        errorMessage = result.error;
      } else if (result.conflict) {
        statusMessage = result.message;
      } else {
        statusMessage = result.message || "Saved.";
      }
    } catch (error) {
      errorMessage = error.message;
    } finally {
      busy = false;
    }
  }

  async function openSettings() {
    await extensionApi.openOptionsPage();
  }
</script>

<svelte:head>
  <title>Save to Markko</title>
</svelte:head>

<div class="popup-shell">
  <div class="grain"></div>

  <p class="eyebrow">Markko Extension</p>
  <h1>Save this page without leaving the tab.</h1>

  {#if activeTab}
    <div class="card current-tab">
      <strong>{activeTab.title || "Untitled page"}</strong>
      <span>{activeTab.url}</span>
    </div>
  {/if}

  <div class="card">
    <label>
      <span>Tags</span>
      <input bind:value={tags} placeholder="reading, product, research" />
    </label>

    <label>
      <span>Collection ID</span>
      <input bind:value={collectionId} placeholder="Optional numeric collection id" />
    </label>

    <button on:click={saveCurrentPage} disabled={busy}>
      {busy ? "Saving..." : "Save to Markko"}
    </button>

    <button class="ghost" on:click={openSettings}>Open settings</button>
  </div>

  {#if settings && !settings.hasAccessToken}
    <p class="hint">
      Configure the API URL and paste a fresh access token in settings. The token is cleared when
      the browser restarts.
    </p>
  {/if}

  {#if statusMessage}
    <p class="banner ok">{statusMessage}</p>
  {/if}

  {#if errorMessage}
    <p class="banner error">{errorMessage}</p>
  {/if}
</div>

<style>
  :global(body) {
    margin: 0;
    min-width: 360px;
    min-height: 420px;
    background:
      radial-gradient(circle at top left, rgba(255, 180, 0, 0.24), transparent 32%),
      linear-gradient(160deg, #f8f1e4, #f2e6db 54%, #fbfaf5);
    color: #181510;
    font-family: "Avenir Next", "Segoe UI Variable Text", sans-serif;
  }

  .popup-shell {
    position: relative;
    padding: 1rem;
    display: grid;
    gap: 0.9rem;
  }

  .grain {
    position: absolute;
    inset: 0;
    background:
      radial-gradient(circle at 20% 20%, rgba(0, 0, 0, 0.03), transparent 18%),
      radial-gradient(circle at 80% 10%, rgba(0, 0, 0, 0.03), transparent 20%);
    pointer-events: none;
  }

  .eyebrow {
    margin: 0;
    text-transform: uppercase;
    letter-spacing: 0.14em;
    font-size: 0.72rem;
    color: #8e5722;
  }

  h1 {
    margin: 0;
    font-size: 1.55rem;
    line-height: 1;
    letter-spacing: -0.04em;
  }

  .card {
    display: grid;
    gap: 0.8rem;
    padding: 1rem;
    border-radius: 24px;
    border: 1px solid rgba(24, 21, 16, 0.08);
    background: rgba(255, 252, 247, 0.76);
    backdrop-filter: blur(12px);
  }

  .current-tab span {
    font-size: 0.86rem;
    color: rgba(24, 21, 16, 0.66);
    word-break: break-word;
  }

  label {
    display: grid;
    gap: 0.35rem;
  }

  label span {
    font-size: 0.78rem;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    color: rgba(24, 21, 16, 0.56);
  }

  input {
    border: 1px solid rgba(24, 21, 16, 0.1);
    border-radius: 16px;
    padding: 0.82rem 0.9rem;
    background: rgba(255, 255, 255, 0.78);
    font: inherit;
  }

  button {
    border: none;
    border-radius: 999px;
    padding: 0.85rem 1rem;
    font: inherit;
    cursor: pointer;
    background: #194e3d;
    color: #fff8ef;
  }

  button.ghost {
    background: rgba(24, 21, 16, 0.08);
    color: #181510;
  }

  button:disabled {
    opacity: 0.6;
    cursor: wait;
  }

  .hint,
  .banner {
    margin: 0;
    font-size: 0.9rem;
  }

  .banner {
    padding: 0.75rem 0.9rem;
    border-radius: 14px;
  }

  .ok {
    background: rgba(25, 78, 61, 0.08);
    color: #194e3d;
  }

  .error {
    background: rgba(142, 45, 32, 0.08);
    color: #8e2d20;
  }
</style>

<script>
  import { onMount } from "svelte";
  import { fetchViewer } from "../shared/markko-api.js";
  import { loadSettings, saveSettings } from "../shared/settings.js";

  let apiBaseUrl = "http://localhost:9002";
  let accessToken = "";
  let defaultCollectionId = "";
  let defaultTags = "";
  let busy = false;
  let statusMessage = "";
  let errorMessage = "";

  onMount(async () => {
    const settings = await loadSettings();
    apiBaseUrl = settings.apiBaseUrl;
    accessToken = settings.accessToken;
    defaultCollectionId = settings.defaultCollectionId;
    defaultTags = settings.defaultTags.join(", ");
  });

  function tagsAsArray() {
    return defaultTags
      .split(",")
      .map((tag) => tag.trim())
      .filter(Boolean);
  }

  async function persist() {
    busy = true;
    statusMessage = "";
    errorMessage = "";

    try {
      await saveSettings({
        apiBaseUrl,
        accessToken,
        defaultCollectionId,
        defaultTags: tagsAsArray()
      });

      statusMessage = "Extension settings saved. The access token stays only for this browser session.";
    } catch (error) {
      errorMessage = error.message;
    } finally {
      busy = false;
    }
  }

  async function testConnection() {
    busy = true;
    statusMessage = "";
    errorMessage = "";

    try {
      const viewer = await fetchViewer({
        apiBaseUrl,
        accessToken
      });

      statusMessage = `Connected as ${viewer.user.name} (${viewer.user.email}).`;
    } catch (error) {
      errorMessage = error.message;
    } finally {
      busy = false;
    }
  }
</script>

<svelte:head>
  <title>Markko Extension Settings</title>
</svelte:head>

<div class="shell">
  <section class="panel intro">
    <p class="eyebrow">Browser Extension</p>
    <h1>Configure where “Save to Markko” sends the current page.</h1>
    <p>
      Paste a Markko access token from `/auth/login`, choose an optional default collection, and
      set default tags for the floating save button. The token is kept only for the current
      browser session and is cleared when the browser restarts.
    </p>
  </section>

  <section class="panel form-panel">
    <label>
      <span>API base URL</span>
      <input bind:value={apiBaseUrl} placeholder="http://localhost:9002" />
    </label>

    <label>
      <span>Access token</span>
      <textarea
        bind:value={accessToken}
        rows="5"
        placeholder="Bearer access token (session-only)"
        spellcheck="false"
      ></textarea>
    </label>

    <div class="split">
      <label>
        <span>Default collection ID</span>
        <input bind:value={defaultCollectionId} placeholder="Optional numeric collection id" />
      </label>

      <label>
        <span>Default tags</span>
        <input bind:value={defaultTags} placeholder="research, later, team" />
      </label>
    </div>

    <div class="actions">
      <button on:click={persist} disabled={busy}>{busy ? "Working..." : "Save settings"}</button>
      <button class="secondary" on:click={testConnection} disabled={busy}>Test connection</button>
    </div>

    {#if statusMessage}
      <p class="banner ok">{statusMessage}</p>
    {/if}

    {#if errorMessage}
      <p class="banner error">{errorMessage}</p>
    {/if}
  </section>
  </div>

<style>
  :global(body) {
    margin: 0;
    min-height: 100vh;
    background:
      radial-gradient(circle at top left, rgba(47, 128, 237, 0.14), transparent 24%),
      linear-gradient(180deg, #f3eee6 0%, #faf8f4 100%);
    color: #1e1b17;
    font-family: "Avenir Next", "Segoe UI Variable Text", sans-serif;
  }

  .shell {
    max-width: 960px;
    margin: 0 auto;
    padding: 2rem 1rem 3rem;
    display: grid;
    gap: 1rem;
  }

  .panel {
    border-radius: 30px;
    padding: 1.4rem;
    background: rgba(255, 252, 247, 0.78);
    border: 1px solid rgba(30, 27, 23, 0.08);
    box-shadow: 0 20px 48px rgba(59, 47, 35, 0.08);
  }

  .eyebrow {
    margin: 0 0 0.7rem;
    text-transform: uppercase;
    letter-spacing: 0.14em;
    font-size: 0.75rem;
    color: #8c551e;
  }

  h1 {
    margin: 0 0 0.8rem;
    font-size: clamp(2rem, 4vw, 3.5rem);
    line-height: 0.95;
    letter-spacing: -0.05em;
    max-width: 12ch;
  }

  .form-panel {
    display: grid;
    gap: 1rem;
  }

  label {
    display: grid;
    gap: 0.4rem;
  }

  span {
    text-transform: uppercase;
    font-size: 0.78rem;
    letter-spacing: 0.08em;
    color: rgba(30, 27, 23, 0.58);
  }

  input,
  textarea {
    width: 100%;
    border: 1px solid rgba(30, 27, 23, 0.1);
    border-radius: 18px;
    padding: 0.9rem 1rem;
    font: inherit;
    background: rgba(255, 255, 255, 0.82);
  }

  textarea {
    resize: vertical;
  }

  .split,
  .actions {
    display: flex;
    gap: 0.9rem;
  }

  .split > * {
    flex: 1;
  }

  button {
    border: none;
    border-radius: 999px;
    padding: 0.9rem 1.15rem;
    font: inherit;
    cursor: pointer;
    background: #194e3d;
    color: #fff8ef;
  }

  .secondary {
    background: #355f87;
  }

  .banner {
    margin: 0;
    padding: 0.8rem 0.95rem;
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

  @media (max-width: 720px) {
    .split,
    .actions {
      flex-direction: column;
    }
  }
</style>

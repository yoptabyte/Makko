<script>
  import { onMount, tick } from "svelte";
  import { createMarkkoClient, resolveDefaultApiBaseUrl } from "./lib/api.js";

  const STORAGE_KEY = "markko-ui-session";
  const THEME_KEY = "markko-ui-theme";
  const VIEWS = [
    { id: "inbox", label: "Library", icon: "inbox" },
    { id: "collections", label: "Collections", icon: "folder_open" },
    { id: "search", label: "Search", icon: "search" },
    { id: "settings", label: "Settings", icon: "settings" }
  ];
  const INBOX_FILTERS = [
    { id: "all", label: "All Items" },
    { id: "ready", label: "Ready" },
    { id: "parsing", label: "Parsing" },
    { id: "exported", label: "Exported" }
  ];

  let apiBaseUrl = resolveDefaultApiBaseUrl();
  let name = "";
  let email = "";
  let password = "";
  let accessToken = "";
  let refreshToken = "";
  let user = null;
  let collections = [];
  let links = [];
  let exportJobs = {};
  let authBusy = false;
  let syncBusy = false;
  let saveBusy = false;
  let collectionBusy = false;
  let statusMessage = "";
  let errorMessage = "";
  let newCollectionName = "";
  let newLinkUrl = "";
  let newLinkTags = "";
  let newLinkCollectionId = "";
  let newLinkExportDirectory = "";
  let newLinkExportFileName = "";
  let autoRefreshBusy = false;
  let activeView = "inbox";
  let previousView = "inbox";
  let captureOpen = false;
  let theme = "dark";
  let inboxQuery = "";
  let inboxFilter = "all";
  let searchQuery = "";
  let searchBusy = false;
  let searchResults = [];
  let searchError = "";
  let selectedSearchCollection = "";
  let selectedSearchTag = "";
  let detailBusy = false;
  let selectedLinkDetail = null;
  let directoryInput;
  let readerJobsSection;

  const client = createMarkkoClient(() => apiBaseUrl, () => accessToken);

  onMount(() => {
    applyTheme(readThemePreference());

    const restoreSession = async () => {
      const stored = safeReadStorage();
      if (!stored) {
        return;
      }

      apiBaseUrl = stored.apiBaseUrl || apiBaseUrl;
      accessToken = stored.accessToken || "";
      refreshToken = stored.refreshToken || "";
      user = stored.user || null;

      if (accessToken) {
        await loadDashboard({ showStatus: false, silent: true });
      }
    };

    void restoreSession();

    const pollHandle = window.setInterval(() => {
      void autoRefreshDashboard();
    }, 3000);

    return () => {
      window.clearInterval(pollHandle);
    };
  });

  function readThemePreference() {
    const stored = localStorage.getItem(THEME_KEY);
    if (stored === "dark" || stored === "light") {
      return stored;
    }

    if (window.matchMedia?.("(prefers-color-scheme: light)").matches) {
      return "light";
    }

    return "dark";
  }

  function applyTheme(nextTheme) {
    theme = nextTheme === "light" ? "light" : "dark";
    document.documentElement.dataset.theme = theme;
    document.documentElement.classList.toggle("dark", theme === "dark");
    localStorage.setItem(THEME_KEY, theme);
  }

  function toggleTheme() {
    applyTheme(theme === "dark" ? "light" : "dark");
  }

  function safeReadStorage() {
    try {
      return JSON.parse(localStorage.getItem(STORAGE_KEY) || "null");
    } catch {
      return null;
    }
  }

  function persistSession() {
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({
        apiBaseUrl,
        accessToken,
        refreshToken,
        user
      })
    );
  }

  function clearSession() {
    accessToken = "";
    refreshToken = "";
    user = null;
    links = [];
    collections = [];
    exportJobs = {};
    searchResults = [];
    selectedLinkDetail = null;
    activeView = "settings";
    persistSession();
  }

  function parseTags(value) {
    return value
      .split(",")
      .map((item) => item.trim())
      .filter(Boolean);
  }

  function normalizeStatus(status) {
    return String(status || "queued").toLowerCase();
  }

  function isReadyLinkStatus(status) {
    return ["ready", "indexed", "parsed", "done", "completed"].includes(normalizeStatus(status));
  }

  function isExportedLinkStatus(status) {
    return ["exported", "done", "completed"].includes(normalizeStatus(status));
  }

  function isActiveLinkStatus(status) {
    return ["queued", "pending", "parsing", "processing", "syncing"].includes(normalizeStatus(status));
  }

  function isActiveExportStatus(status) {
    return ["queued", "pending", "exporting", "processing", "running"].includes(normalizeStatus(status));
  }

  function prettifyStatus(status) {
    return String(status || "Queued")
      .replace(/[_-]+/g, " ")
      .replace(/\b\w/g, (letter) => letter.toUpperCase());
  }

  function statusTone(status) {
    const normalized = normalizeStatus(status);

    if (["ready", "indexed", "parsed", "exported", "done", "completed"].includes(normalized)) {
      return "tone-success";
    }

    if (["parsing", "syncing", "queued", "pending", "processing", "running", "exporting"].includes(normalized)) {
      return "tone-warning";
    }

    if (["failed", "error", "deleted"].includes(normalized)) {
      return "tone-danger";
    }

    return "tone-neutral";
  }

  function collectionName(collectionId) {
    const collection = collections.find((entry) => String(entry.id) === String(collectionId));
    return collection?.name || "Unsorted";
  }

  function collectionLinks(collectionId) {
    return links
      .filter((link) => String(link.collectionId) === String(collectionId))
      .sort((left, right) => new Date(right.savedAt || 0) - new Date(left.savedAt || 0));
  }

  function linkDomain(url) {
    try {
      return new URL(url).hostname.replace(/^www\./, "");
    } catch {
      return "external";
    }
  }

  function formatDate(value) {
    if (!value) {
      return "Recent";
    }

    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
      return "Recent";
    }

    return new Intl.DateTimeFormat(undefined, {
      year: "numeric",
      month: "short",
      day: "2-digit"
    }).format(date);
  }

  function formatDateTime(value) {
    if (!value) {
      return "Not captured yet";
    }

    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
      return "Not captured yet";
    }

    return new Intl.DateTimeFormat(undefined, {
      year: "numeric",
      month: "short",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit"
    }).format(date);
  }

  function userInitials(value) {
    const source = String(value || "MK")
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((part) => part[0]?.toUpperCase() || "");

    return source.join("") || "MK";
  }

  function goTo(view) {
    if (view === "reader") {
      return;
    }

    previousView = view;
    activeView = view;
  }

  function closeCapture() {
    captureOpen = false;
  }

  function openCapture() {
    captureOpen = true;
  }

  async function finishLogin(session) {
    accessToken = session.accessToken || session.token || "";
    refreshToken = session.refreshToken || "";
    user = session.user || null;
    persistSession();
    await loadDashboard({ showStatus: false, silent: true });
  }

  async function login() {
    authBusy = true;
    statusMessage = "";
    errorMessage = "";

    try {
      const session = await client.login({ email, password });
      await finishLogin(session);
      statusMessage = "Session created. Dashboard synced.";
    } catch (error) {
      errorMessage =
        error.status === 401
          ? "Invalid credentials. If this is a fresh database, create an account first."
          : error.message;
    } finally {
      authBusy = false;
    }
  }

  async function register() {
    authBusy = true;
    statusMessage = "";
    errorMessage = "";

    try {
      await client.register({ name, email, password });
      const session = await client.login({ email, password });
      await finishLogin(session);
      statusMessage = "Account created. Dashboard synced.";
    } catch (error) {
      errorMessage =
        error.status === 409
          ? "Account already exists. Use Sign in with the same email and password."
          : error.message;
    } finally {
      authBusy = false;
    }
  }

  async function loadDashboard({ showStatus = true, silent = false } = {}) {
    if (!accessToken) {
      return;
    }

    syncBusy = true;
    if (!silent) {
      errorMessage = "";
    }

    try {
      const [profile, nextCollections, linkResponse] = await Promise.all([
        client.me(),
        client.listCollections(),
        client.listLinks()
      ]);

      user = profile?.user || user;
      collections = nextCollections || [];
      links = linkResponse?.links || [];
      persistSession();

      if (showStatus) {
        statusMessage = "Dashboard synced.";
      }
    } catch (error) {
      if (error.status === 401) {
        clearSession();
        errorMessage = "Session expired. Sign in again.";
      } else if (!silent) {
        errorMessage = error.message;
      }
    } finally {
      syncBusy = false;
    }
  }

  async function refreshTrackedExports(silent = true) {
    const trackedLinkIds = Object.keys(exportJobs).map(Number).filter((value) => Number.isFinite(value));
    if (trackedLinkIds.length === 0) {
      return;
    }

    try {
      const nextEntries = await Promise.all(
        trackedLinkIds.map(async (linkId) => [linkId, await client.exportStatus(linkId)])
      );

      exportJobs = {
        ...exportJobs,
        ...Object.fromEntries(nextEntries)
      };
    } catch (error) {
      if (!silent) {
        errorMessage = error.message;
      }
    }
  }

  function hasTrackedExportWork() {
    const jobLists = Object.values(exportJobs);
    if (jobLists.length === 0) {
      return false;
    }

    return jobLists.some((jobs) => {
      if (!jobs || jobs.length === 0) {
        return true;
      }

      return jobs.some((job) => isActiveExportStatus(job.status));
    });
  }

  async function autoRefreshDashboard() {
    if (autoRefreshBusy || !accessToken) {
      return;
    }

    const shouldRefreshLinks = links.some((link) => isActiveLinkStatus(link.status));
    const shouldRefreshExports = hasTrackedExportWork();

    if (!shouldRefreshLinks && !shouldRefreshExports) {
      return;
    }

    autoRefreshBusy = true;

    try {
      if (shouldRefreshLinks) {
        await loadDashboard({ showStatus: false, silent: true });
      }

      if (shouldRefreshExports || Object.keys(exportJobs).length > 0) {
        await refreshTrackedExports(true);
      }
    } finally {
      autoRefreshBusy = false;
    }
  }

  async function pickSystemDirectory() {
    errorMessage = "";

    try {
      const response = await client.pickVaultDirectory();
      if (response?.path) {
        newLinkExportDirectory = response.path;
        return;
      }
    } catch (error) {
      // Fall back to browser-side directory selection below.
    }

    if (typeof window !== "undefined" && typeof window.showDirectoryPicker === "function") {
      try {
        const handle = await window.showDirectoryPicker({ mode: "read" });
        newLinkExportDirectory = handle.name || "";
        return;
      } catch (error) {
        if (error?.name !== "AbortError") {
          errorMessage = error.message || "Failed to select a directory.";
        }
        return;
      }
    }

    directoryInput?.click();
  }

  function handleDirectoryInputChange(event) {
    const files = Array.from(event.currentTarget?.files || []);
    if (files.length === 0) {
      return;
    }

    const firstPath = files[0]?.webkitRelativePath || files[0]?.name || "";
    const topLevelDirectory = firstPath.split("/").filter(Boolean)[0] || "";

    if (!topLevelDirectory) {
      errorMessage = "Failed to resolve the selected folder.";
      return;
    }

    newLinkExportDirectory = topLevelDirectory;
    event.currentTarget.value = "";
  }

  async function createCollection() {
    collectionBusy = true;
    statusMessage = "";
    errorMessage = "";

    const trimmedName = newCollectionName.trim();

    if (!trimmedName) {
      errorMessage = "Collection name cannot be empty.";
      collectionBusy = false;
      return;
    }

    try {
      const created = await client.createCollection(trimmedName);
      collections = [
        ...collections,
        {
          ...created,
          createdAt: new Date().toISOString(),
          linkCount: 0
        }
      ].sort((left, right) => left.name.localeCompare(right.name));
      newCollectionName = "";
      statusMessage = "Collection created.";
    } catch (error) {
      errorMessage = error.message;
    } finally {
      collectionBusy = false;
    }
  }

  async function saveLink() {
    saveBusy = true;
    statusMessage = "";
    errorMessage = "";

    try {
      const payload = {
        url: newLinkUrl,
        tags: parseTags(newLinkTags)
      };

      if (newLinkCollectionId) {
        payload.collectionId = Number(newLinkCollectionId);
      }

      if (newLinkExportDirectory.trim()) {
        payload.exportDirectory = newLinkExportDirectory.trim();
      }

      if (newLinkExportFileName.trim()) {
        payload.exportFileName = newLinkExportFileName.trim();
      }

      const created = await client.createLink(payload);
      newLinkUrl = "";
      newLinkTags = "";
      newLinkCollectionId = "";
      newLinkExportDirectory = "";
      newLinkExportFileName = "";
      captureOpen = false;
      await loadDashboard({ showStatus: false, silent: true });
      if (created?.id) {
        await inspectExport(created.id, true);
      }
      statusMessage = "Link saved and queued for parsing.";
    } catch (error) {
      errorMessage = error.message;
    } finally {
      saveBusy = false;
    }
  }

  async function inspectExport(linkId, silent = false) {
    if (!silent) {
      statusMessage = "";
      errorMessage = "";
    }

    try {
      exportJobs = {
        ...exportJobs,
        [linkId]: await client.exportStatus(linkId)
      };
    } catch (error) {
      if (!silent) {
        errorMessage = error.message;
      }
    }
  }

  async function queueReexport(linkId) {
    statusMessage = "";
    errorMessage = "";

    try {
      await client.reexport(linkId);
      await inspectExport(linkId);
      statusMessage = `Re-export queued for link #${linkId}.`;
    } catch (error) {
      errorMessage = error.message;
    }
  }

  async function removeExport(linkId) {
    statusMessage = "";
    errorMessage = "";

    try {
      await client.deleteExport(linkId);
      await inspectExport(linkId);
      statusMessage = `Delete job queued for link #${linkId}.`;
    } catch (error) {
      errorMessage = error.message;
    }
  }

  async function runSearch() {
    searchBusy = true;
    searchError = "";

    const normalized = searchQuery.trim();
    if (!normalized || !accessToken) {
      searchResults = [];
      searchBusy = false;
      return;
    }

    try {
      searchResults = await client.searchLinks(normalized);
    } catch (error) {
      searchError = error.message;
      searchResults = localSearch(normalized);
    } finally {
      searchBusy = false;
    }
  }

  function localSearch(query) {
    const normalized = query.toLowerCase();

    return links
      .filter((link) => {
        return [link.title, link.url, ...(link.tags || []), collectionName(link.collectionId)]
          .filter(Boolean)
          .some((value) => String(value).toLowerCase().includes(normalized));
      })
      .map((link) => ({
        id: link.id,
        title: link.title || link.url,
        url: link.url,
        tags: link.tags || [],
        collection: collectionName(link.collectionId),
        highlights: {
          title: null,
          content: makeSearchSnippet(link, query)
        }
      }));
  }

  async function openReader(linkId, sourceView = activeView) {
    previousView = sourceView === "reader" ? previousView : sourceView;
    activeView = "reader";
    detailBusy = true;
    errorMessage = "";

    try {
      selectedLinkDetail = await client.getLink(linkId);
      await inspectExport(linkId, true);
    } catch (error) {
      errorMessage = error.message;
      activeView = previousView || "inbox";
    } finally {
      detailBusy = false;
    }
  }

  function closeReader() {
    activeView = previousView || "inbox";
  }

  function escapeHtml(value) {
    return String(value || "")
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;")
      .replaceAll('"', "&quot;")
      .replaceAll("'", "&#39;");
  }

  function sanitizeHighlight(html) {
    return escapeHtml(html).replaceAll("&lt;mark&gt;", "<mark>").replaceAll("&lt;/mark&gt;", "</mark>");
  }

  function renderInlineMarkdown(value) {
    return escapeHtml(value)
      .replace(/`([^`]+)`/g, "<code>$1</code>")
      .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
      .replace(/\*([^*]+)\*/g, "<em>$1</em>")
      .replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g, '<a href="$2" target="_blank" rel="noreferrer">$1</a>');
  }

  function markdownBlocks(markdown) {
    if (!markdown) {
      return [];
    }

    const lines = String(markdown).replace(/\r/g, "").split("\n");
    const blocks = [];
    let paragraph = [];
    let list = [];
    let quote = [];
    let code = [];
    let codeFence = false;

    const flushParagraph = () => {
      if (paragraph.length > 0) {
        blocks.push({ type: "paragraph", text: paragraph.join(" ") });
        paragraph = [];
      }
    };

    const flushList = () => {
      if (list.length > 0) {
        blocks.push({ type: "list", items: list });
        list = [];
      }
    };

    const flushQuote = () => {
      if (quote.length > 0) {
        blocks.push({ type: "quote", text: quote.join(" ") });
        quote = [];
      }
    };

    const flushCode = () => {
      if (code.length > 0) {
        blocks.push({ type: "code", text: code.join("\n") });
        code = [];
      }
    };

    for (const line of lines) {
      if (line.trim().startsWith("```")) {
        flushParagraph();
        flushList();
        flushQuote();
        if (codeFence) {
          flushCode();
        }
        codeFence = !codeFence;
        continue;
      }

      if (codeFence) {
        code.push(line);
        continue;
      }

      const headingMatch = line.match(/^(#{1,6})\s+(.*)$/);
      if (headingMatch) {
        flushParagraph();
        flushList();
        flushQuote();
        blocks.push({
          type: "heading",
          level: headingMatch[1].length,
          text: headingMatch[2]
        });
        continue;
      }

      const listMatch = line.match(/^[-*+]\s+(.*)$/);
      if (listMatch) {
        flushParagraph();
        flushQuote();
        list.push(listMatch[1]);
        continue;
      }

      const quoteMatch = line.match(/^>\s?(.*)$/);
      if (quoteMatch) {
        flushParagraph();
        flushList();
        quote.push(quoteMatch[1]);
        continue;
      }

      if (!line.trim()) {
        flushParagraph();
        flushList();
        flushQuote();
        continue;
      }

      paragraph.push(line.trim());
    }

    flushParagraph();
    flushList();
    flushQuote();
    flushCode();

    return blocks;
  }

  function makeSearchSnippet(link, query) {
    const source = link.contentMd || link.title || link.url || "";
    const snippet = String(source).slice(0, 220);
    const safe = escapeHtml(snippet);
    if (!query) {
      return safe;
    }

    const pattern = query.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    return safe.replace(new RegExp(pattern, "gi"), (match) => `<mark>${match}</mark>`);
  }

  function countJobsByLink(linkId) {
    return (exportJobs[linkId] || []).length;
  }

  function linkFileLabel(link) {
    return (
      link.exportFileName ||
      link.title ||
      link.url.replace(/^https?:\/\//, "").replace(/^www\./, "")
    );
  }

  function selectSearchTag(tag) {
    selectedSearchTag = selectedSearchTag === tag ? "" : tag;
  }

  async function showReaderJobs() {
    if (!selectedLinkDetail) {
      return;
    }

    await inspectExport(selectedLinkDetail.id);
    await tick();

    if (readerJobsSection) {
      readerJobsSection.scrollIntoView({ behavior: "smooth", block: "start" });
    } else {
      statusMessage = "No export jobs recorded for this link yet.";
      window.scrollTo({ top: 0, behavior: "smooth" });
    }
  }

  async function handleReaderReexport() {
    if (!selectedLinkDetail) {
      return;
    }

    await queueReexport(selectedLinkDetail.id);
    await loadDashboard({ showStatus: false, silent: true });
    await showReaderJobs();
  }

  async function handleReaderDelete() {
    if (!selectedLinkDetail) {
      return;
    }

    await removeExport(selectedLinkDetail.id);
    await loadDashboard({ showStatus: false, silent: true });
    window.scrollTo({ top: 0, behavior: "smooth" });
  }

  function createCollectionRows(items) {
    const root = [];
    const branchMap = new Map();

    function ensureBranch(parent, key, depth, segment) {
      const path = key;
      if (branchMap.has(path)) {
        return branchMap.get(path);
      }

      const node = {
        path,
        label: segment,
        depth,
        children: [],
        count: 0,
        directCount: 0,
        collection: null
      };

      branchMap.set(path, node);
      parent.push(node);
      return node;
    }

    for (const collection of items) {
      const parts = String(collection.name || "")
        .split("/")
        .map((part) => part.trim())
        .filter(Boolean);

      let parent = root;
      let currentPath = "";

      parts.forEach((part, index) => {
        currentPath = currentPath ? `${currentPath}/${part}` : part;
        const node = ensureBranch(parent, currentPath, index, part);

        if (index === parts.length - 1) {
          node.collection = collection;
          node.directCount = collection.resolvedLinkCount || 0;
        }

        parent = node.children;
      });
    }

    function walk(nodes) {
      return nodes.map((node) => {
        node.children = walk(node.children);
        node.count = node.directCount + node.children.reduce((sum, child) => sum + child.count, 0);
        return node;
      });
    }

    function flatten(nodes, rows = []) {
      for (const node of nodes) {
        rows.push(node);
        flatten(node.children, rows);
      }
      return rows;
    }

    return flatten(walk(root));
  }

  $: isAuthenticated = Boolean(accessToken);
  $: collectionCards = [...collections]
    .map((collection) => ({
      ...collection,
      resolvedLinkCount:
        typeof collection.linkCount === "number"
          ? collection.linkCount
          : links.filter((link) => String(link.collectionId) === String(collection.id)).length
    }))
    .sort((left, right) => left.name.localeCompare(right.name));
  $: collectionRows = createCollectionRows(collectionCards);
  $: exportEntries = Object.entries(exportJobs).flatMap(([linkId, jobs]) =>
    (jobs || []).map((job, index) => ({
      ...job,
      key: `${linkId}-${index}`,
      linkId: Number(linkId)
    }))
  );
  $: readyCount = links.filter((link) => isReadyLinkStatus(link.status)).length;
  $: parsingCount = links.filter((link) => isActiveLinkStatus(link.status)).length;
  $: vaultUsage = `${(links.length * 0.028).toFixed(1)} GB`;
  $: filteredInboxLinks = links.filter((link) => {
    const query = inboxQuery.trim().toLowerCase();
    const filterPass =
      inboxFilter === "all"
        ? true
        : inboxFilter === "ready"
          ? isReadyLinkStatus(link.status)
          : inboxFilter === "parsing"
            ? isActiveLinkStatus(link.status)
            : isExportedLinkStatus(link.status);

    const queryPass = !query
      ? true
      : [link.title, link.url, collectionName(link.collectionId), ...(link.tags || [])]
          .filter(Boolean)
          .some((value) => String(value).toLowerCase().includes(query));

    return filterPass && queryPass;
  });
  $: visibleSearchResults = searchResults.filter((result) => {
    const collectionPass = !selectedSearchCollection || result.collection === selectedSearchCollection;
    const tagPass = !selectedSearchTag || (result.tags || []).includes(selectedSearchTag);
    return collectionPass && tagPass;
  });
  $: selectedLinkExportJobs = selectedLinkDetail ? exportJobs[selectedLinkDetail.id] || [] : [];
  $: readerBlocks = markdownBlocks(selectedLinkDetail?.contentMd || "");
</script>

<svelte:head>
  <title>Markko</title>
  <meta
    name="description"
    content="Markko vault surface with inbox, collections, search, reader, export tracking, and theme switching."
  />
</svelte:head>

<div class="shell">
  <aside class="sidebar">
    <div class="sidebar-brand">
      <div class="brand-badge">{userInitials(user?.name)}</div>
      <div>
        <p class="sidebar-kicker">Vault Surface</p>
        <h1>Markko</h1>
      </div>
    </div>

    <nav class="sidebar-nav" aria-label="Primary">
      {#each VIEWS as view}
        <button
          type="button"
          class:active={activeView === view.id}
          class="sidebar-link"
          on:click={() => goTo(view.id)}
        >
          <span class="material-symbols-outlined">{view.icon}</span>
          <span>{view.label}</span>
          {#if view.id === "inbox"}
            <small>{links.length}</small>
          {:else if view.id === "collections"}
            <small>{collectionCards.length}</small>
          {:else if view.id === "search"}
            <small>{searchResults.length}</small>
          {:else}
            <small>{isAuthenticated ? "Live" : "Offline"}</small>
          {/if}
        </button>
      {/each}
    </nav>

  </aside>

  <div class="main-shell">
    <header class="topbar">
      <button type="button" class="icon-button mobile-only" on:click={() => goTo("inbox")}>
        <span class="material-symbols-outlined">menu</span>
      </button>

      <div class="topbar-actions">
        <button type="button" class="icon-button" on:click={() => goTo("search")}>
          <span class="material-symbols-outlined">search</span>
        </button>
        <button type="button" class="icon-button" on:click={toggleTheme}>
          <span class="material-symbols-outlined">
            {theme === "dark" ? "light_mode" : "dark_mode"}
          </span>
        </button>
        <button type="button" class="primary-button" on:click={openCapture}>Save URL</button>
      </div>
    </header>

    <main class={`workspace ${activeView === "reader" ? "reader-mode" : ""}`}>
      {#if statusMessage}
        <div class="notice success">{statusMessage}</div>
      {/if}

      {#if errorMessage}
        <div class="notice error">{errorMessage}</div>
      {/if}

      {#if activeView === "inbox"}
        <section class="mobile-screen">
          <div class="screen-header">
            <div>
              <h2>Library</h2>
              <p>Recently saved links, parsing state, and export activity.</p>
            </div>
            <div class="status-chip subtle">{isAuthenticated ? "Online" : "Offline"}</div>
          </div>

          <label class="search-field">
            <span class="material-symbols-outlined">search</span>
            <input bind:value={inboxQuery} placeholder="Search saved links..." />
          </label>

          <div class="chip-row">
            {#each INBOX_FILTERS as filter}
              <button
                type="button"
                class={`filter-chip ${inboxFilter === filter.id ? "active" : ""}`}
                on:click={() => (inboxFilter = filter.id)}
              >
                {filter.label}
              </button>
            {/each}
          </div>

          <div class="section-head">
            <span>Recently Saved</span>
            <button type="button" class="ghost-icon" on:click={() => loadDashboard()}>
              <span class="material-symbols-outlined">filter_list</span>
            </button>
          </div>

          <div class="inbox-list">
            {#if filteredInboxLinks.length === 0}
              <article class="empty-card">
                <strong>{isAuthenticated ? "No matching links." : "Sign in to load your vault."}</strong>
                <p>
                  {isAuthenticated
                    ? "Adjust the search, clear the active chip, or save a new URL."
                    : "Authentication moved into Settings so the main workspace stays focused on the archive."}
                </p>
              </article>
            {:else}
              {#each filteredInboxLinks as link}
                <button
                  type="button"
                  class="inbox-card card-button"
                  on:click={() => openReader(link.id, "inbox")}
                >
                  <div class="inbox-card-head">
                    <h3>{link.title || link.url}</h3>
                    <div class={`status-badge ${statusTone(link.status)}`}>
                      {#if statusTone(link.status) === "tone-success"}
                        <span class="dot"></span>
                      {:else if statusTone(link.status) === "tone-warning"}
                        <span class="dot pulse"></span>
                      {:else}
                        <span class="material-symbols-outlined tiny">check_circle</span>
                      {/if}
                      {prettifyStatus(link.status)}
                    </div>
                  </div>

                  <div class="inbox-meta">
                    <span>{linkDomain(link.url)}</span>
                    <span class="divider"></span>
                    <span class="meta-inline">
                      <span class="material-symbols-outlined tiny">folder_open</span>
                      {collectionName(link.collectionId)}
                    </span>
                  </div>
                </button>
              {/each}
            {/if}
          </div>

          <section class="quote-card">
            <p>"Your digital library is a map of your mind."</p>
          </section>
        </section>
      {/if}

      {#if activeView === "collections"}
        <section class="desktop-screen">
          <div class="screen-header">
            <div>
              <h2>Collections</h2>
              <p>Organize your digital legacy into structured vaults.</p>
            </div>
          </div>

          <div class="collections-layout">
            <section class="panel tree-panel">
              <div class="tree-list">
                {#if collectionRows.length === 0}
                  <article class="empty-card slim">
                    <strong>No collections yet.</strong>
                    <p>Create the first one with a path like `Research / Cognition`.</p>
                  </article>
                {:else}
                  {#each collectionRows as row}
                    <div class="tree-entry">
                      <button type="button" class="tree-row" style={`--depth: ${row.depth};`}>
                        <div class="tree-row-main">
                          <span class="material-symbols-outlined tiny">
                            {row.children.length > 0 ? (row.depth === 0 ? "expand_more" : "subdirectory_arrow_right") : "folder"}
                          </span>
                          <span class="material-symbols-outlined folder-icon">
                            {row.depth === 0 ? "folder_open" : "folder"}
                          </span>
                          <span class="tree-label">{row.label}</span>
                        </div>
                        <span class="tree-count">{row.count}</span>
                      </button>

                      {#if row.collection}
                        <div class="tree-files" style={`--depth: ${row.depth + 1};`}>
                          {#each collectionLinks(row.collection.id).slice(0, 6) as link}
                            <button
                              type="button"
                              class="tree-file"
                              on:click={() => openReader(link.id, "collections")}
                            >
                              <span class="material-symbols-outlined tiny">description</span>
                              <span>{linkFileLabel(link)}</span>
                            </button>
                          {/each}

                          {#if collectionLinks(row.collection.id).length > 6}
                            <div class="tree-more">
                              +{collectionLinks(row.collection.id).length - 6} more files
                            </div>
                          {/if}
                        </div>
                      {/if}
                    </div>
                  {/each}
                {/if}
              </div>
            </section>

            <div class="side-column">
              <section class="panel form-panel">
                <div class="panel-head">
                  <div>
                    <p class="panel-label">Create New Collection</p>
                    <h3>Structured workspace naming</h3>
                  </div>
                </div>
                <label>
                  <span>Name</span>
                  <input bind:value={newCollectionName} placeholder="Research / Search Infrastructure" />
                </label>
                <button
                  type="button"
                  class="primary-button wide"
                  on:click={createCollection}
                  disabled={collectionBusy || !accessToken || !newCollectionName.trim()}
                >
                  {collectionBusy ? "Creating..." : "Create New Collection"}
                </button>
              </section>

              <section class="panel export-panel">
                <div class="panel-head">
                  <div>
                    <p class="panel-label">Export Jobs</p>
                    <h3>Pipeline state</h3>
                  </div>
                  <button type="button" class="ghost-icon" on:click={() => refreshTrackedExports(false)}>
                    <span class="material-symbols-outlined">sync</span>
                  </button>
                </div>

                <div class="job-stack">
                  {#if exportEntries.length === 0}
                    <article class="empty-card slim">
                      <strong>No tracked exports yet.</strong>
                      <p>Open a link or inspect an export job to cache it here.</p>
                    </article>
                  {:else}
                    {#each exportEntries.slice(0, 4) as job}
                      <article class="job-card">
                        <div class="job-card-head">
                          <div>
                            <strong>Link #{job.linkId}</strong>
                            <span>{formatDate(job.updatedAt || job.createdAt)}</span>
                          </div>
                          <div class={`status-badge ${statusTone(job.status)}`}>{prettifyStatus(job.status)}</div>
                        </div>
                        <div class="job-progress">
                          <span style={`width: ${isActiveExportStatus(job.status) ? 65 : normalizeStatus(job.status) === "failed" ? 100 : 100}%`}></span>
                        </div>
                      </article>
                    {/each}
                  {/if}
                </div>
              </section>

              <section class="capacity-panel">
                <div class="capacity-copy">
                  <p class="panel-label">Vault Capacity</p>
                  <strong>{vaultUsage}</strong>
                  <span>{links.length} curated notes across {Math.max(collectionCards.length, 1)} collections.</span>
                </div>
                <span class="material-symbols-outlined capacity-icon">database</span>
              </section>
            </div>
          </div>
        </section>
      {/if}

      {#if activeView === "search"}
        <section class="desktop-screen search-screen">
          <div class="screen-header">
            <div>
              <h2>Search Archive</h2>
              <p>Elasticsearch-backed retrieval with highlights and collection filters.</p>
            </div>
          </div>

          <form class="search-cluster" on:submit|preventDefault={runSearch}>
            <label class="search-field large">
              <span class="material-symbols-outlined">search</span>
              <input bind:value={searchQuery} placeholder="Query cognitive nodes..." />
            </label>
            <button type="submit" class="secondary-button" disabled={!accessToken || searchBusy}>
              {searchBusy ? "Searching..." : "Search"}
            </button>
          </form>

          <div class="chip-row">
            <button type="button" class={`filter-chip ${!selectedSearchCollection ? "active" : ""}`} on:click={() => (selectedSearchCollection = "")}>
              All Collections
            </button>
            {#each collectionCards.slice(0, 4) as collection}
              <button
                type="button"
                class={`filter-chip ${selectedSearchCollection === collection.name ? "active" : ""}`}
                on:click={() => (selectedSearchCollection = collection.name)}
              >
                {collection.name}
              </button>
            {/each}
            {#if selectedSearchTag}
              <button type="button" class="filter-chip active" on:click={() => (selectedSearchTag = "")}>
                #{selectedSearchTag}
              </button>
            {/if}
          </div>

          {#if searchError}
            <div class="subtle-note">Search fallback active: {searchError}</div>
          {/if}

          <div class="search-results">
            {#if visibleSearchResults.length === 0}
              <article class="empty-card">
                <strong>{searchQuery ? "No search hits." : "Search is ready."}</strong>
                <p>
                  {searchQuery
                    ? "Try a broader query, another collection chip, or wait until parsing completes."
                    : "Enter a query to search title, content, and tags."}
                </p>
              </article>
            {:else}
              {#each visibleSearchResults as result}
                <div
                  class="search-result"
                  role="button"
                  tabindex="0"
                  on:click={() => openReader(result.id, "search")}
                  on:keydown={(event) => (event.key === "Enter" || event.key === " ") && openReader(result.id, "search")}
                >
                  <div class="search-meta">
                    <span>{result.collection || "Unsorted"}</span>
                    <span class="line"></span>
                    <span>{formatDate(links.find((item) => item.id === result.id)?.savedAt)}</span>
                  </div>
                  <h3>{@html sanitizeHighlight(result.highlights?.title || escapeHtml(result.title || result.url))}</h3>
                  <p class="search-snippet">
                    {@html sanitizeHighlight(result.highlights?.content || makeSearchSnippet(links.find((item) => item.id === result.id) || result, searchQuery))}
                  </p>
                  <div class="tag-row">
                    {#each result.tags || [] as tag}
                      <button type="button" class="tag-chip" on:click|stopPropagation={() => selectSearchTag(tag)}>
                        #{tag}
                      </button>
                    {/each}
                  </div>
                </div>
              {/each}
            {/if}
          </div>
        </section>
      {/if}

      {#if activeView === "settings"}
        <section class="desktop-screen settings-screen">
          <div class="screen-header">
            <div>
              <h2>Settings</h2>
              <p>Authentication, theme control, sync, and vault workflow settings.</p>
            </div>
            <div class={`status-chip ${isAuthenticated ? "connected" : "subtle"}`}>
              {isAuthenticated ? "Authenticated" : "Offline"}
            </div>
          </div>

          <div class="settings-layout">
            <section class="panel auth-panel">
              <div class="panel-head">
                <div>
                  <p class="panel-label">Session</p>
                  <h3>{isAuthenticated ? "Active Markko session" : "Authenticate against Markko"}</h3>
                </div>
              </div>

              <div class="form-grid">
                <label>
                  <span>Name</span>
                  <input bind:value={name} placeholder="Markko User" />
                </label>
                <label>
                  <span>Email</span>
                  <input bind:value={email} placeholder="test@example.com" />
                </label>
                <label>
                  <span>Password</span>
                  <input type="password" bind:value={password} placeholder="••••••••" />
                </label>
              </div>

              <div class="action-row">
                <button type="button" class="primary-button" on:click={login} disabled={authBusy || !email || !password}>
                  {authBusy ? "Working..." : "Sign In"}
                </button>
                <button
                  type="button"
                  class="secondary-button"
                  on:click={register}
                  disabled={authBusy || !name || !email || !password}
                >
                  {authBusy ? "Working..." : "Create Account"}
                </button>
                <button type="button" class="secondary-button" on:click={() => loadDashboard()} disabled={syncBusy || !accessToken}>
                  {syncBusy ? "Syncing..." : "Sync Dashboard"}
                </button>
                <button type="button" class="ghost-button" on:click={clearSession} disabled={!accessToken}>
                  Log Out
                </button>
              </div>

              <p class="support-copy">Use this panel for account access, sync, and session control.</p>
            </section>

            <section class="theme-stack">
              <article class={`theme-card ${theme === "dark" ? "selected" : ""}`}>
                <div class="theme-preview dark-preview"></div>
                <div class="theme-copy">
                  <strong>Primary Vault</strong>
                  <span>Dense dark archive panels, muted graphite backgrounds, lavender accents.</span>
                </div>
                <button type="button" class="secondary-button" on:click={() => applyTheme("dark")}>
                  Use Dark
                </button>
              </article>

              <article class={`theme-card ${theme === "light" ? "selected" : ""}`}>
                <div class="theme-preview light-preview"></div>
                <div class="theme-copy">
                  <strong>Light Atelier</strong>
                  <span>Warm editorial surfaces and softer paper-like contrast for daytime work.</span>
                </div>
                <button type="button" class="secondary-button" on:click={() => applyTheme("light")}>
                  Use Light
                </button>
              </article>
            </section>
          </div>
        </section>
      {/if}

      {#if activeView === "reader"}
        <section class="reader-screen">
          <div class="reader-top">
            <button type="button" class="icon-button" on:click={closeReader}>
              <span class="material-symbols-outlined">arrow_back</span>
            </button>
            <div class="reader-actions">
              {#if selectedLinkDetail}
                <button type="button" class="secondary-button compact" on:click={() => inspectExport(selectedLinkDetail.id)}>
                  Export Status
                </button>
                <button type="button" class="secondary-button compact" on:click={() => queueReexport(selectedLinkDetail.id)}>
                  Re-export
                </button>
              {/if}
            </div>
          </div>

          {#if detailBusy}
            <article class="empty-card">
              <strong>Loading saved entry...</strong>
              <p>Fetching link details and markdown content from the API.</p>
            </article>
          {:else if selectedLinkDetail}
            <section class="reader-metadata">
              <div>
                <span>Source URL</span>
                <a href={selectedLinkDetail.url} target="_blank" rel="noreferrer">{selectedLinkDetail.url}</a>
              </div>
              <div>
                <span>Captured On</span>
                <strong>{formatDateTime(selectedLinkDetail.savedAt)}</strong>
              </div>
              <div>
                <span>Collection</span>
                <strong>{collectionName(selectedLinkDetail.collectionId)}</strong>
              </div>
            </section>

            <article class="reader-article">
              <header class="reader-header">
                <div class={`status-badge ${statusTone(selectedLinkDetail.status)}`}>{prettifyStatus(selectedLinkDetail.status)}</div>
                <h2>{selectedLinkDetail.title || selectedLinkDetail.url}</h2>
                <p>
                  {selectedLinkDetail.readingTimeMin
                    ? `${selectedLinkDetail.readingTimeMin} min read`
                    : "Parsed markdown will appear here once the worker finishes processing."}
                </p>
              </header>

              {#if readerBlocks.length === 0}
                <div class="reader-empty">
                  <p>No markdown content is available yet for this link.</p>
                </div>
              {:else}
                <div class="markdown-flow">
                  {#each readerBlocks as block}
                    {#if block.type === "heading"}
                      {#if block.level === 1}
                        <h1>{@html renderInlineMarkdown(block.text)}</h1>
                      {:else if block.level === 2}
                        <h2>{@html renderInlineMarkdown(block.text)}</h2>
                      {:else}
                        <h3>{@html renderInlineMarkdown(block.text)}</h3>
                      {/if}
                    {:else if block.type === "paragraph"}
                      <p>{@html renderInlineMarkdown(block.text)}</p>
                    {:else if block.type === "quote"}
                      <blockquote>{@html renderInlineMarkdown(block.text)}</blockquote>
                    {:else if block.type === "list"}
                      <ul>
                        {#each block.items as item}
                          <li>{@html renderInlineMarkdown(item)}</li>
                        {/each}
                      </ul>
                    {:else if block.type === "code"}
                      <pre><code>{block.text}</code></pre>
                    {/if}
                  {/each}
                </div>
              {/if}
            </article>

            <nav class="reader-toolbar">
              <a class="toolbar-action" href={selectedLinkDetail.url} target="_blank" rel="noreferrer">
                <span class="material-symbols-outlined">open_in_new</span>
                <span>Open</span>
              </a>
              <button type="button" class="toolbar-action" on:click={showReaderJobs}>
                <span class="material-symbols-outlined">folder_shared</span>
                <span>Jobs</span>
              </button>
              <button type="button" class="toolbar-action" on:click={handleReaderReexport}>
                <span class="material-symbols-outlined">ios_share</span>
                <span>Re-export</span>
              </button>
              <button type="button" class="toolbar-action danger" on:click={handleReaderDelete}>
                <span class="material-symbols-outlined">delete</span>
                <span>Delete</span>
              </button>
            </nav>

            {#if selectedLinkExportJobs.length > 0}
              <section class="panel inline-jobs" bind:this={readerJobsSection}>
                <div class="panel-head">
                  <div>
                    <p class="panel-label">Export Jobs</p>
                    <h3>Tracked history for this entry</h3>
                  </div>
                </div>
                <div class="job-stack">
                  {#each selectedLinkExportJobs as job}
                    <article class="job-card">
                      <div class="job-card-head">
                        <div>
                          <strong>{job.vaultPath || "Vault pipeline"}</strong>
                          <span>{formatDate(job.completedAt || job.createdAt)}</span>
                        </div>
                        <div class={`status-badge ${statusTone(job.status)}`}>{prettifyStatus(job.status)}</div>
                      </div>
                    </article>
                  {/each}
                </div>
              </section>
            {/if}
          {/if}
        </section>
      {/if}
    </main>
  </div>

  {#if activeView !== "reader"}
    <button type="button" class="fab" on:click={openCapture}>
      <span class="material-symbols-outlined">add</span>
    </button>
  {/if}

  {#if activeView !== "reader"}
    <nav class="bottom-nav">
      {#each VIEWS as view}
        <button
          type="button"
          class={`bottom-link ${activeView === view.id ? "active" : ""}`}
          on:click={() => goTo(view.id)}
        >
          <span class="material-symbols-outlined">{view.icon}</span>
          <span>{view.label}</span>
        </button>
      {/each}
    </nav>
  {/if}
</div>

<input
  bind:this={directoryInput}
  class="hidden-directory-input"
  type="file"
  webkitdirectory=""
  directory=""
  multiple
  on:change={handleDirectoryInputChange}
/>

{#if captureOpen}
  <div class="modal-backdrop" role="button" tabindex="0" on:click={closeCapture} on:keydown={(event) => event.key === "Escape" && closeCapture()}>
    <div class="modal-card" role="dialog" tabindex="-1" aria-modal="true" on:click|stopPropagation on:keydown|stopPropagation>
      <div class="panel-head">
        <div>
          <p class="panel-label">Capture</p>
          <h3>Save a link into the vault</h3>
        </div>
        <div class={`status-chip ${isAuthenticated ? "connected" : "subtle"}`}>{isAuthenticated ? "Ready" : "Locked"}</div>
      </div>

      <label>
        <span>URL</span>
        <input bind:value={newLinkUrl} placeholder="https://example.com/article" />
      </label>

      <div class="form-grid">
        <label>
          <span>Tags</span>
          <input bind:value={newLinkTags} placeholder="research, design-system, notes" />
        </label>

        <label>
          <span>Collection</span>
          <select bind:value={newLinkCollectionId}>
            <option value="">Unsorted</option>
            {#each collectionCards as collection}
              <option value={collection.id}>{collection.name}</option>
            {/each}
          </select>
        </label>

        <label>
          <span>Vault Directory</span>
          <div class="picker-field picker-actions">
            <button type="button" class="secondary-button compact" on:click={pickSystemDirectory}>
              Choose Folder
            </button>
            {#if newLinkExportDirectory}
              <button type="button" class="ghost-button compact" on:click={() => (newLinkExportDirectory = "")}>
                Clear
              </button>
            {/if}
          </div>
          <small class="field-hint">
            {newLinkExportDirectory
              ? `Selected folder: ${newLinkExportDirectory}`
              : "Uses the collection default until you choose a system folder."}
          </small>
        </label>

        <label>
          <span>Markdown File Name</span>
          <input bind:value={newLinkExportFileName} placeholder="digital-cognition" />
        </label>
      </div>

      <div class="action-row">
        <button type="button" class="primary-button" on:click={saveLink} disabled={saveBusy || !accessToken || !newLinkUrl}>
          {saveBusy ? "Saving..." : "Save Link"}
        </button>
        <button type="button" class="ghost-button" on:click={closeCapture}>Cancel</button>
      </div>
    </div>
  </div>
{/if}

# Markko

Markko is a Scala 2.13 bookmark pipeline built from three runtime parts:

- `modules/api`: Play API, auth, Redis queue producer, WebSocket feed
- `modules/worker`: parser/export worker, Elasticsearch indexer, Obsidian sync
- `docker-compose.yml`: MySQL, Redis, Elasticsearch, Prometheus, Grafana
- `ui`: Svelte/Vite dashboard for auth, links, collections, and export control
- `extension`: Chrome/Firefox extension with popup/options/content-script "Save to Markko"

## Repository Readiness

This repository is intended for local development by default.

- `.env` is ignored and should never be committed.
- `vault/`, `target/`, `dist/`, `node_modules/`, `.metals/`, `.vscode/`, and `out/` are local-only artifacts.
- The extension keeps its access token in session-only extension storage and clears it when the browser restarts.
- Server-side system folder picking and absolute-path export are disabled by default and must be explicitly enabled for local desktop setups.

## End-to-End Flow

1. `POST /links` stores the link in MySQL and pushes `linkId` into `queue:parse`.
2. `ParserSupervisor` polls Redis and hands the job to `ParserWorker`.
3. `ParserWorker` fetches the page, converts HTML to Markdown, updates MySQL, indexes the document in Elasticsearch, and publishes `notifications:parsed`.
4. `FeedActor` subscribes to `notifications:parsed` and forwards the event into `/ws/feed`.
5. After a successful parse, `ExporterWorker` writes the note into the Obsidian vault and updates `export_jobs`.

Without MySQL + Redis + Elasticsearch + a running worker, the full scenario `add link -> parse -> index -> export` will not complete.

## Local Run

1. Create `.env` from `.env.example`.
   The Nix dev shell loads `.env` automatically, so optional local desktop flags will be available to `sbt runApi` and `sbt runWorker`.
2. Start infrastructure:

```bash
docker compose up -d mysql redis elasticsearch
```

3. Start the API:

```bash
nix develop --command sbt runApi
```

4. Start the worker in a second terminal:

```bash
nix develop --command sbt runWorker
```

5. By default exports go into the local `./vault` workspace directory.
   If you want to choose a folder from the UI, use the folder picker there instead of setting a fixed vault path in `.env`.
6. Optional local desktop-only overrides can be uncommented in `.env`:

```dotenv
MARKKO_ENABLE_SYSTEM_PICKER=true
MARKKO_ALLOW_ABSOLUTE_EXPORT_PATHS=true
```

Those flags are only needed if you want the API process itself to open a native folder picker or allow absolute export paths selected from the UI.

Prometheus and Grafana are optional for the main bookmark pipeline:

```bash
docker compose up -d prometheus grafana
```

## Notes

- Redis queues: `queue:parse`, `queue:export`, `queue:delete`
- Redis pub/sub channel for the feed: `notifications:parsed`
- Search depends on Elasticsearch being reachable.
- Flyway migrations run on API startup.
- `modules/api/conf/application.conf` and `modules/worker/src/main/resources/application.conf` contain local-development defaults and are not production-hardened.

## Frontend Surfaces

### Svelte UI

```bash
cd ui
npm install
npm run dev
```

The UI expects the API at `http://localhost:9002` by default and stores the access token in local browser storage.
The UI can fall back to a browser-side folder chooser, but absolute-path export requires the local opt-in flags shown above.

### Browser Extension

```bash
cd extension
npm install
npm run build
```

Load `extension/dist` as an unpacked extension in Chrome or Firefox Developer Edition.
The extension options page stores API URL and defaults persistently, but keeps the access token only in session storage. Expect to paste a fresh token after restarting the browser.

The extension exposes:

- popup for the current tab
- options page for API URL, access token, default collection, and default tags
- floating `Save to Markko` button on any regular page

## Before Publishing

If you plan to initialize a new Git repository and push to GitHub:

1. Do not commit `.env`.
2. Keep `MARKKO_ENABLE_SYSTEM_PICKER` and `MARKKO_ALLOW_ABSOLUTE_EXPORT_PATHS` disabled unless you are on a trusted local machine.
3. Review `modules/api/conf/application.conf` before using the project outside local development.
4. Make sure ignored local artifacts such as `vault/`, `target/`, `dist/`, and `node_modules/` are not force-added.

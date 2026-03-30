# Markko

Markko saves links, parses them into Markdown, indexes them for search, and can export them into an Obsidian vault.

Project parts:

- `modules/api` — Play API
- `modules/worker` — parser/export worker
- `ui` — Svelte dashboard
- `extension` — browser extension
- `docker-compose.yml` — local infrastructure

## Local Run

1. Create local env:

```bash
cp .env.example .env
```

2. Start infrastructure:

```bash
docker compose up -d mysql redis elasticsearch
```

3. Start API:

```bash
nix develop --command sbt runApi
```

4. Start worker in a second terminal:

```bash
nix develop --command sbt runWorker
```

5. Start UI:

```bash
cd ui
npm install
npm run dev
```

Default local URLs:

- API: `http://127.0.0.1:9002`
- UI: `http://127.0.0.1:4173`
- Grafana: `http://127.0.0.1:3000`
- Prometheus: `http://127.0.0.1:9090`

## Obsidian Export

By default exports go into the local `./vault` directory.

If you want to pick a folder from the UI, enable these flags in `.env`:

```dotenv
MARKKO_ENABLE_SYSTEM_PICKER=true
MARKKO_ALLOW_ABSOLUTE_EXPORT_PATHS=true
```

## Browser Extension

Build the extension:

```bash
cd extension
npm install
npm run build
```

Then load `extension/dist` as an unpacked extension.

## Notes

- `.env` is local-only and should not be committed.
- Prometheus scrapes the API on `9002` and the worker metrics on `9095`.
- Grafana uses the local Prometheus datasource automatically.

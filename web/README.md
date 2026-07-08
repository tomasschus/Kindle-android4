# Biblioteca PDF — web platform

Next.js app for uploading PDFs and managing your personal library. Postgres
(via Prisma) stores metadata/highlights/progress; the PDF bytes themselves
live in [Garage](https://garagehq.deuxfleurs.fr/) (S3-compatible object
storage). See `../docs/API.md` for the full REST contract the Android app
syncs against.

## Stack

- **Next.js 16** (App Router, Route Handlers as the API layer)
- **Prisma 7** with the `pg` driver adapter (`@prisma/adapter-pg`) — Prisma 7
  requires a driver adapter, there's no more implicit `datasource.url` engine
- **Garage / S3** via `@aws-sdk/client-s3` + `@aws-sdk/lib-storage`
- **Auth**: username + password (bcrypt), JWT bearer tokens (shared between
  the web session cookie and the Android app), no third-party auth provider

## Setup

1. Start Postgres + Garage: `docker compose up -d` from the repo root (see
   `../infra/README.md` for details, including how to fetch the generated
   Garage credentials).
2. `cp .env.example .env` and fill in `DATABASE_URL` / `JWT_SECRET` /
   `GARAGE_*`.
3. `npm install`
4. `npx prisma migrate deploy` (or `migrate dev` while iterating on the schema)
5. `npm run dev`
6. Visit `http://localhost:3000`, use "¿Primera vez? Crear cuenta" to create
   your account (self-signup is only allowed while the user table is empty,
   or if you set `ALLOW_SIGNUP=true`).

## Project layout

```
src/app/api/          Route Handlers implementing docs/API.md
src/app/login/         Login/signup page
src/app/dashboard/      PDF library UI (upload/list/delete)
src/lib/prisma.ts       Prisma client (pg driver adapter)
src/lib/storage.ts      Garage/S3 client (upload/download/delete, Range support)
src/lib/auth.ts         Password hashing, JWT sign/verify, request auth resolution
src/lib/serialize.ts    DB row -> API JSON shaping (matches docs/API.md)
prisma/schema.prisma    User / Document / Highlight / ReadingProgress models
```

## Notes on the data model

- `Document.deletedAt` and `Highlight.deleted` are soft-delete/tombstone
  markers — kept around (not hard-deleted) so `/api/sync` can tell other
  devices something was removed since their last sync.
- `Highlight.rects` stores normalized (0..1) rectangles per page so
  highlights survive being re-rendered at a different zoom/resolution on the
  Android app.

## Testing performed during development

No docker registry access was available in the sandbox this was built in, so
Garage itself couldn't be started live there. What *was* verified end-to-end
locally: Postgres (native install) + a local S3-compatible mock server
(`s3rver`, used only for that manual test, not a project dependency) —
register, login (cookie + Bearer), upload, list, download (including HTTP
Range requests), delete, highlights CRUD, progress, and `/api/sync`
(including delete tombstones) all round-tripped correctly. The login/signup
→ dashboard → upload flow was also driven through a real Chromium browser.
`next build`, `tsc --noEmit`, and `eslint` all pass clean.

Garage itself speaks the S3 API, which is exactly what `@aws-sdk/client-s3`
targets — the storage code has no Garage-specific logic — but you should
still smoke-test `docker compose up` end-to-end on a machine with normal
internet access before relying on it.

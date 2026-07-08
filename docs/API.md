# API Contract — Web Platform ⇄ Android App

Base URL: configurable, e.g. `https://your-server.example.com/api`

All request/response bodies are JSON unless noted. All authenticated requests
send `Authorization: Bearer <token>`.

## Auth

### POST /api/auth/login
Request: `{ "username": string, "password": string }`
Response 200: `{ "token": string, "user": { "id": string, "username": string } }`
Response 401: `{ "error": "invalid_credentials" }`

Token is a JWT signed by the server, valid 90 days, containing `{ sub: userId }`.
Both the web session (cookie) and the Android app (Bearer header) are issued
from this same endpoint — there is one user table and one password.

### POST /api/auth/logout
Clears the web session cookie. Android app just discards the local token
(tokens are stateless JWTs, no server-side revocation list in v1).

## Documents

### GET /api/documents
Response 200: `{ "documents": Document[] }`

```
Document = {
  id: string
  title: string
  filename: string          // the originally uploaded file's name (.pdf or .epub)
  sizeBytes: number
  pageCount: number | null
  checksum: string          // sha256 hex of the originally uploaded file
  hasPdf: boolean           // false for documents uploaded directly as EPUB (no PDF exists)
  epubStatus: "ready" | "failed" | null | undefined
  createdAt: string         // ISO 8601
  updatedAt: string
}
```

A document can originate from either an uploaded PDF or an uploaded EPUB —
either way it's a single library entry (`Document` row), not two. PDFs are
**always** converted server-side to a reflowable EPUB on upload (best-effort
text extraction, see `lib/epub.ts`); `epubStatus` reflects that conversion's
outcome (`"ready"` / `"failed"`). EPUBs uploaded directly are stored as-is
with `epubStatus: "ready"` and `hasPdf: false` — there is no PDF for those
(no EPUB->PDF conversion). The Android app treats `epubStatus === "ready"` as
"download and read the EPUB" (reflowable WebView reader) and falls back to
the PDF otherwise. `pageCount` and `checksum` describe whichever file was
originally uploaded -- Android doesn't have a way to detect the EPUB itself
changing after the fact, so don't regenerate/replace `epubKey` for an
existing document id.

### POST /api/documents  (multipart/form-data)
Fields: `file` (a `.pdf` or `.epub` file), `title` (string, optional —
defaults to filename). PDF uploads are always converted to EPUB; EPUB
uploads are stored directly. Non-PDF/EPUB files are rejected with
`unsupported_format`.
Response 201: `Document`

### DELETE /api/documents/:id
Response 204.

### GET /api/documents/:id/download
Streams the raw PDF bytes. 404s (`pdf_not_available`) when `hasPdf` is
`false` (EPUB-only document). Supports `Range` requests (needed for large
PDFs on a slow tablet connection / resuming interrupted downloads).
Headers: `Content-Type: application/pdf`, `Content-Length`, `Accept-Ranges: bytes`.

### GET /api/documents/:id/epub
Streams the converted EPUB. 404s (`epub_not_available`) unless `epubStatus`
is `"ready"`. No `Range` support -- Android always does a plain full
download for this one.
Headers: `Content-Type: application/epub+zip`, `Content-Length`.

## Highlights

Highlights are stored as one or more normalized rectangles per page
(normalized to 0..1 of page width/height so they survive different render
resolutions). PDF-only for now -- the Android EPUB reader doesn't create
highlights (rectangle coordinates don't mean anything on reflowable text).

```
Highlight = {
  id: string
  documentId: string
  page: number             // 0-indexed
  rects: { x: number, y: number, w: number, h: number }[]
  color: string             // "#RRGGBB"
  note: string | null
  createdAt: string
  updatedAt: string
  deleted: boolean          // tombstone for sync
}
```

### GET /api/documents/:id/highlights
Response 200: `{ "highlights": Highlight[] }`

### POST /api/documents/:id/highlights
Request: `{ page, rects, color, note? }`
Response 201: `Highlight`

### PUT /api/highlights/:id
Request: partial `{ rects?, color?, note? }`
Response 200: `Highlight`

### DELETE /api/highlights/:id
Soft-deletes (tombstone) so other devices can sync the deletion.
Response 204.

## Reading progress

For EPUBs, `page` is the 0-indexed spine (chapter) position, not a real page
number -- there's no fixed pagination on reflowable text, so this is
chapter-level granularity only.

```
Progress = { documentId: string, page: number, updatedAt: string }
```

### GET /api/documents/:id/progress
Response 200: `Progress | null`

### PUT /api/documents/:id/progress
Request: `{ page: number }`
Response 200: `Progress`

## Incremental sync (used by the Android app)

### GET /api/sync?since=<ISO8601 timestamp>
Returns everything changed since the given timestamp, in one round trip, to
minimize requests over a slow/old-tablet wifi connection. Omit `since` (or
pass nothing) for a full sync on first login.

Response 200:
```
{
  "serverTime": string,           // ISO 8601 — client stores this as the next `since`
  "documents": Document[],        // created/updated/deleted since `since`
  "documentsDeleted": string[],   // ids of documents deleted since `since`
  "highlights": Highlight[],      // includes tombstones (deleted:true)
  "progress": Progress[]
}
```

Android sync algorithm:
1. Load `lastSyncAt` from local SQLite (null on first run).
2. `GET /api/sync?since=lastSyncAt`.
3. Upsert documents into local DB; for any not yet downloaded, queue a
   background download — `GET /api/documents/:id/epub` when `epubStatus ===
   "ready"`, otherwise `GET /api/documents/:id/download` (compare `checksum`
   against locally stored one to skip unchanged files).
4. Remove documents in `documentsDeleted` (and their local file + highlights).
5. Upsert highlights (apply tombstones as deletes); local-only highlights
   created offline are POSTed up first, then merged.
6. Push any local progress changes made offline via PUT, then store
   `serverTime` as the new `lastSyncAt`.

## Error format
All errors: `{ "error": string, "message"?: string }` with a 4xx/5xx status.

const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const Module = require('node:module');
const ts = require('typescript');
const { NextRequest } = require('next/server');

process.env.JWT_SECRET = 'reader-contract-test-secret';
let highlights, progress;
const document = { id: 'book', ownerId: 'reader', deletedAt: null, epubStatus: 'ready' };
const prisma = {
  document: { findFirst: async ({ where }) => where.id === document.id && where.ownerId === document.ownerId ? document : null },
  highlight: {
    create: async ({ data }) => {
      const row = { id: `h${highlights.length}`, createdAt: new Date(), updatedAt: new Date(), deleted: false, ...data };
      highlights.push(row); return row;
    },
    upsert: async ({ where, create }) => highlights.find(h => h.ownerId === where.ownerId_clientId.ownerId && h.clientId === where.ownerId_clientId.clientId)
      ?? prisma.highlight.create({ data: create }),
    findFirst: async ({ where }) => highlights.find(h => h.id === where.id && h.ownerId === where.ownerId),
    update: async ({ where, data }) => Object.assign(highlights.find(h => h.id === where.id), data),
  },
  readingProgress: {
    upsert: async ({ where, create, update }) => {
      const key = where.documentId_ownerId_format;
      let row = progress.find(p => p.documentId === key.documentId && p.ownerId === key.ownerId && p.format === key.format);
      if (row) Object.assign(row, update);
      else { row = { ...create, updatedAt: new Date() }; progress.push(row); }
      return row;
    },
    findUnique: async ({ where }) => {
      const key = where.documentId_ownerId_format;
      return progress.find(p => p.documentId === key.documentId && p.ownerId === key.ownerId && p.format === key.format) ?? null;
    },
  },
};

// Execute the real TypeScript handlers with only persistence replaced. This
// checks their HTTP contract without touching the deployed personal library.
const modules = new Map();
function load(file) {
  const absolute = path.resolve(__dirname, '..', file);
  if (modules.has(absolute)) return modules.get(absolute).exports;
  const mod = new Module(absolute, module);
  mod.filename = absolute;
  mod.paths = Module._nodeModulePaths(path.dirname(absolute));
  modules.set(absolute, mod);
  const requireOriginal = mod.require.bind(mod);
  mod.require = name => name === '@/lib/prisma' ? { prisma }
    : name.startsWith('@/') ? load(`src/${name.slice(2)}.ts`) : requireOriginal(name);
  mod._compile(ts.transpileModule(fs.readFileSync(absolute, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, esModuleInterop: true },
  }).outputText, absolute);
  return mod.exports;
}
const auth = load('src/lib/auth.ts');
const create = load('src/app/api/documents/[id]/highlights/route.ts');
const update = load('src/app/api/highlights/[id]/route.ts');
const reading = load('src/app/api/documents/[id]/progress/route.ts');
const clientId = 'local-12345678-1234-1234-1234-123456789abc';
const epub = { clientId, page: 2, rects: [], anchorQuote: 'una palabra', anchorPrefix: 'antes ', anchorSuffix: ' después', color: '#ffeb3b' };
function request(body, method = 'POST', user = 'reader', suffix = '') {
  return new NextRequest(`http://localhost/api/documents/book/progress${suffix}`, {
    method, headers: { Authorization: `Bearer ${auth.signToken(user)}`, 'Content-Type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
}
const book = { params: Promise.resolve({ id: 'book' }) };
beforeEach(() => { highlights = []; progress = []; });

test('EPUB anchors survive creation and serialization, with no rectangles', async () => {
  const response = await create.POST(request(epub), book);
  assert.equal(response.status, 201);
  const body = await response.json();
  assert.equal(body.anchorQuote, epub.anchorQuote);
  assert.equal(body.anchorPrefix, epub.anchorPrefix);
  assert.equal(body.anchorSuffix, epub.anchorSuffix);
  assert.equal(body.clientId, clientId);
  assert.deepEqual(body.rects, []);
});
test('lost POST responses can be retried without duplicates or resurrecting deletions', async () => {
  const first = await (await create.POST(request(epub), book)).json();
  highlights[0].deleted = true;
  const second = await (await create.POST(request(epub), book)).json();
  assert.equal(second.id, first.id);
  assert.equal(second.deleted, true);
  assert.equal(highlights.length, 1);
});
test('PDF rectangles remain compatible', async () => {
  const response = await create.POST(request({ page: 3, rects: [{ x: .1, y: .2, w: .3, h: .1 }] }), book);
  assert.equal(response.status, 201);
});
test('reject missing anchors, mixed anchors, invalid rectangles and empty quotes', async () => {
  for (const body of [
    { page: 0, rects: [] }, { ...epub, anchorQuote: '' },
    { ...epub, rects: [{ x: 0, y: 0, w: .1, h: .1 }] },
    { page: 0, rects: [{ x: .9, y: 0, w: .5, h: .1 }] },
  ]) assert.equal((await create.POST(request(body), book)).status, 400);
  assert.equal(highlights.length, 0);
});
test('EPUB color and notes can be edited without manufacturing PDF rectangles', async () => {
  const h = await (await create.POST(request(epub), book)).json();
  const response = await update.PUT(request({ color: '#64b5f6', note: null }, 'PUT'), { params: Promise.resolve({ id: h.id }) });
  assert.equal(response.status, 200);
  assert.equal((await response.json()).anchorQuote, epub.anchorQuote);
});
test('an update cannot remove the last anchor or edit a tombstone', async () => {
  const h = await (await create.POST(request(epub), book)).json();
  const params = { params: Promise.resolve({ id: h.id }) };
  assert.equal((await update.PUT(request({ anchorQuote: null }, 'PUT'), params)).status, 400);
  await update.DELETE(request(undefined, 'DELETE'), params);
  assert.equal((await update.PUT(request({ color: '#64b5f6' }, 'PUT'), params)).status, 409);
});
test('PDF pages and EPUB chapters are independent and restored by format', async () => {
  await reading.PUT(request({ format: 'pdf', page: 27 }, 'PUT'), book);
  await reading.PUT(request({ format: 'epub', page: 2 }, 'PUT'), book);
  assert.equal(progress.length, 2);
  assert.equal((await (await reading.GET(request(undefined, 'GET', 'reader', '?format=pdf'), book)).json()).page, 27);
  assert.equal((await (await reading.GET(request(undefined, 'GET', 'reader', '?format=epub'), book)).json()).page, 2);
});
test('old clients default to the document primary format', async () => {
  const response = await reading.PUT(request({ page: 2 }, 'PUT'), book);
  assert.equal((await response.json()).format, 'epub');
});
test('reject unknown reading formats and isolate users', async () => {
  assert.equal((await reading.PUT(request({ page: 0, format: 'unknown' }, 'PUT'), book)).status, 400);
  assert.equal((await reading.GET(request(undefined, 'GET', 'reader', '?format=unknown'), book)).status, 400);
  assert.equal((await create.POST(request(epub, 'POST', 'another-user'), book)).status, 404);
  assert.equal((await reading.GET(request(undefined, 'GET', 'another-user'), book)).status, 404);
});

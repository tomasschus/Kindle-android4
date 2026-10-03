ALTER TABLE "Highlight" ADD COLUMN "clientId" TEXT,
  ADD COLUMN "anchorQuote" TEXT, ADD COLUMN "anchorPrefix" TEXT, ADD COLUMN "anchorSuffix" TEXT;
CREATE UNIQUE INDEX "Highlight_ownerId_clientId_key" ON "Highlight"("ownerId", "clientId");
ALTER TABLE "ReadingProgress" ADD COLUMN "format" TEXT NOT NULL DEFAULT 'pdf';
-- Old clients downloaded EPUB whenever it was ready. Preserve their place in that format.
UPDATE "ReadingProgress" p SET "format" = 'epub' FROM "Document" d
  WHERE p."documentId" = d."id" AND d."epubStatus" = 'ready';
DROP INDEX "ReadingProgress_documentId_ownerId_key";
CREATE UNIQUE INDEX "ReadingProgress_documentId_ownerId_format_key"
  ON "ReadingProgress"("documentId", "ownerId", "format");

import type { Document, Highlight, ReadingProgress } from "@/generated/prisma/client";

export function serializeDocument(doc: Document) {
  return {
    id: doc.id,
    title: doc.title,
    filename: doc.filename,
    sizeBytes: doc.sizeBytes,
    pageCount: doc.pageCount,
    checksum: doc.checksum,
    hasPdf: doc.s3Key != null,
    epubStatus: doc.epubStatus,
    createdAt: doc.createdAt,
    updatedAt: doc.updatedAt,
  };
}

export function serializeHighlight(h: Highlight) {
  return {
    id: h.id,
    documentId: h.documentId,
    page: h.page,
    rects: h.rects,
    color: h.color,
    note: h.note,
    createdAt: h.createdAt,
    updatedAt: h.updatedAt,
    deleted: h.deleted,
  };
}

export function serializeProgress(p: ReadingProgress) {
  return {
    documentId: p.documentId,
    page: p.page,
    updatedAt: p.updatedAt,
  };
}

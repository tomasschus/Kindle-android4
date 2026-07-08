import { NextRequest, NextResponse } from "next/server";
import { prisma } from "@/lib/prisma";
import { AuthError, jsonError, requireUserId } from "@/lib/http";
import { serializeDocument, serializeHighlight, serializeProgress } from "@/lib/serialize";
import type { Document } from "@/generated/prisma/client";

export async function GET(request: NextRequest) {
  try {
    const userId = requireUserId(request);
    const sinceParam = request.nextUrl.searchParams.get("since");
    const since = sinceParam ? new Date(sinceParam) : new Date(0);
    if (Number.isNaN(since.getTime())) return jsonError(400, "invalid_since");

    // Server time is captured before querying so a write that lands mid-request
    // is safely picked up again on the next sync rather than being missed.
    const serverTime = new Date();

    const [changedDocuments, highlights, progress] = await Promise.all([
      prisma.document.findMany({
        where: { ownerId: userId, updatedAt: { gt: since } },
      }),
      prisma.highlight.findMany({
        where: { ownerId: userId, updatedAt: { gt: since } },
      }),
      prisma.readingProgress.findMany({
        where: { ownerId: userId, updatedAt: { gt: since } },
      }),
    ]);

    const documents = changedDocuments.filter((d: Document) => d.deletedAt === null);
    const documentsDeleted = changedDocuments
      .filter((d: Document) => d.deletedAt !== null)
      .map((d: Document) => d.id);

    return NextResponse.json({
      serverTime,
      documents: documents.map(serializeDocument),
      documentsDeleted,
      highlights: highlights.map(serializeHighlight),
      progress: progress.map(serializeProgress),
    });
  } catch (err) {
    if (err instanceof AuthError) return jsonError(401, "unauthorized");
    throw err;
  }
}

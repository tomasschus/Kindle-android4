import { NextRequest, NextResponse } from "next/server";
import { createHighlightSchema } from "@/lib/reader-validation";
import { prisma } from "@/lib/prisma";
import { AuthError, jsonError, requireUserId } from "@/lib/http";
import { serializeHighlight } from "@/lib/serialize";

export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  try {
    const userId = requireUserId(request);
    const { id } = await params;
    const doc = await prisma.document.findFirst({ where: { id, ownerId: userId } });
    if (!doc) return jsonError(404, "not_found");

    const highlights = await prisma.highlight.findMany({
      where: { documentId: doc.id, ownerId: userId },
      orderBy: { page: "asc" },
    });
    return NextResponse.json({ highlights: highlights.map(serializeHighlight) });
  } catch (err) {
    if (err instanceof AuthError) return jsonError(401, "unauthorized");
    throw err;
  }
}

export async function POST(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  try {
    const userId = requireUserId(request);
    const { id } = await params;
    const doc = await prisma.document.findFirst({ where: { id, ownerId: userId, deletedAt: null } });
    if (!doc) return jsonError(404, "not_found");

    const parsed = createHighlightSchema.safeParse(await request.json().catch(() => null));
    if (!parsed.success) return jsonError(400, "invalid_body");
    const { page, rects, color, note, clientId, anchorQuote, anchorPrefix, anchorSuffix } = parsed.data;

    const data = {
        documentId: doc.id,
        ownerId: userId,
        page,
        rects,
        color: color ?? "#ffeb3b",
        note: note ?? null,
        clientId, anchorQuote, anchorPrefix, anchorSuffix,
    };
    // Repeating a POST after a lost response must not duplicate a highlight
    // or resurrect a tombstone. The unique key also handles concurrent retries.
    const highlight = clientId
      ? await prisma.highlight.upsert({
          where: { ownerId_clientId: { ownerId: userId, clientId } },
          create: data, update: {},
        })
      : await prisma.highlight.create({ data });
    if (highlight.documentId !== doc.id) return jsonError(409, "client_id_conflict");

    return NextResponse.json(serializeHighlight(highlight), { status: 201 });
  } catch (err) {
    if (err instanceof AuthError) return jsonError(401, "unauthorized");
    throw err;
  }
}

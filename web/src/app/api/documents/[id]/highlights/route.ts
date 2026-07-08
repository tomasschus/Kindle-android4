import { NextRequest, NextResponse } from "next/server";
import { z } from "zod";
import { prisma } from "@/lib/prisma";
import { AuthError, jsonError, requireUserId } from "@/lib/http";
import { serializeHighlight } from "@/lib/serialize";

const rectSchema = z.object({
  x: z.number().min(0).max(1),
  y: z.number().min(0).max(1),
  w: z.number().min(0).max(1),
  h: z.number().min(0).max(1),
});

const createSchema = z.object({
  page: z.number().int().min(0),
  rects: z.array(rectSchema).min(1),
  color: z.string().regex(/^#[0-9a-fA-F]{6}$/).optional(),
  note: z.string().max(2000).nullish(),
});

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

    const parsed = createSchema.safeParse(await request.json().catch(() => null));
    if (!parsed.success) return jsonError(400, "invalid_body");
    const { page, rects, color, note } = parsed.data;

    const highlight = await prisma.highlight.create({
      data: {
        documentId: doc.id,
        ownerId: userId,
        page,
        rects,
        color: color ?? "#ffeb3b",
        note: note ?? null,
      },
    });

    return NextResponse.json(serializeHighlight(highlight), { status: 201 });
  } catch (err) {
    if (err instanceof AuthError) return jsonError(401, "unauthorized");
    throw err;
  }
}

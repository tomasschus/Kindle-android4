import { NextRequest, NextResponse } from "next/server";
import { updateHighlightSchema, validHighlightAnchor } from "@/lib/reader-validation";
import { prisma } from "@/lib/prisma";
import { AuthError, jsonError, requireUserId } from "@/lib/http";
import { serializeHighlight } from "@/lib/serialize";

export async function PUT(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  try {
    const userId = requireUserId(request);
    const { id } = await params;
    const existing = await prisma.highlight.findFirst({ where: { id, ownerId: userId } });
    if (!existing) return jsonError(404, "not_found");

    const parsed = updateHighlightSchema.safeParse(await request.json().catch(() => null));
    if (!parsed.success) return jsonError(400, "invalid_body");
    if (existing.deleted) return jsonError(409, "highlight_deleted");
    if (!validHighlightAnchor({ ...existing, ...parsed.data })) return jsonError(400, "invalid_anchor");

    const highlight = await prisma.highlight.update({
      where: { id: existing.id },
      data: parsed.data,
    });

    return NextResponse.json(serializeHighlight(highlight));
  } catch (err) {
    if (err instanceof AuthError) return jsonError(401, "unauthorized");
    throw err;
  }
}

export async function DELETE(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  try {
    const userId = requireUserId(request);
    const { id } = await params;
    const existing = await prisma.highlight.findFirst({ where: { id, ownerId: userId } });
    if (!existing) return jsonError(404, "not_found");

    await prisma.highlight.update({
      where: { id: existing.id },
      data: { deleted: true },
    });

    return new NextResponse(null, { status: 204 });
  } catch (err) {
    if (err instanceof AuthError) return jsonError(401, "unauthorized");
    throw err;
  }
}

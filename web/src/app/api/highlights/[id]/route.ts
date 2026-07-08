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

const updateSchema = z.object({
  rects: z.array(rectSchema).min(1).optional(),
  color: z.string().regex(/^#[0-9a-fA-F]{6}$/).optional(),
  note: z.string().max(2000).nullish(),
});

export async function PUT(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  try {
    const userId = requireUserId(request);
    const { id } = await params;
    const existing = await prisma.highlight.findFirst({ where: { id, ownerId: userId } });
    if (!existing) return jsonError(404, "not_found");

    const parsed = updateSchema.safeParse(await request.json().catch(() => null));
    if (!parsed.success) return jsonError(400, "invalid_body");

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

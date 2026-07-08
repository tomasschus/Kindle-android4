import { NextRequest, NextResponse } from "next/server";
import { z } from "zod";
import { prisma } from "@/lib/prisma";
import { AuthError, jsonError, requireUserId } from "@/lib/http";
import { serializeProgress } from "@/lib/serialize";

const bodySchema = z.object({ page: z.number().int().min(0) });

export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  try {
    const userId = requireUserId(request);
    const { id } = await params;
    const doc = await prisma.document.findFirst({ where: { id, ownerId: userId } });
    if (!doc) return jsonError(404, "not_found");

    const progress = await prisma.readingProgress.findUnique({
      where: { documentId_ownerId: { documentId: doc.id, ownerId: userId } },
    });
    return NextResponse.json(progress ? serializeProgress(progress) : null);
  } catch (err) {
    if (err instanceof AuthError) return jsonError(401, "unauthorized");
    throw err;
  }
}

export async function PUT(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  try {
    const userId = requireUserId(request);
    const { id } = await params;
    const doc = await prisma.document.findFirst({ where: { id, ownerId: userId, deletedAt: null } });
    if (!doc) return jsonError(404, "not_found");

    const parsed = bodySchema.safeParse(await request.json().catch(() => null));
    if (!parsed.success) return jsonError(400, "invalid_body");

    const progress = await prisma.readingProgress.upsert({
      where: { documentId_ownerId: { documentId: doc.id, ownerId: userId } },
      create: { documentId: doc.id, ownerId: userId, page: parsed.data.page },
      update: { page: parsed.data.page },
    });

    return NextResponse.json(serializeProgress(progress));
  } catch (err) {
    if (err instanceof AuthError) return jsonError(401, "unauthorized");
    throw err;
  }
}

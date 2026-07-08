import { NextRequest, NextResponse } from "next/server";
import { prisma } from "@/lib/prisma";
import { deleteObject } from "@/lib/storage";
import { AuthError, jsonError, requireUserId } from "@/lib/http";
import { serializeDocument } from "@/lib/serialize";

export const runtime = "nodejs";

export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  try {
    const userId = requireUserId(request);
    const { id } = await params;
    const doc = await prisma.document.findFirst({
      where: { id, ownerId: userId, deletedAt: null },
    });
    if (!doc) return jsonError(404, "not_found");
    return NextResponse.json(serializeDocument(doc));
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
    const doc = await prisma.document.findFirst({
      where: { id, ownerId: userId, deletedAt: null },
    });
    if (!doc) return jsonError(404, "not_found");

    if (doc.s3Key) await deleteObject(doc.s3Key);
    if (doc.epubKey) await deleteObject(doc.epubKey);
    await prisma.document.update({
      where: { id: doc.id },
      data: { deletedAt: new Date() },
    });

    return new NextResponse(null, { status: 204 });
  } catch (err) {
    if (err instanceof AuthError) return jsonError(401, "unauthorized");
    throw err;
  }
}

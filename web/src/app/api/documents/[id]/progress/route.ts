import { NextRequest, NextResponse } from "next/server";
import { progressSchema, formatSchema } from "@/lib/reader-validation";
import { prisma } from "@/lib/prisma";
import { AuthError, jsonError, requireUserId } from "@/lib/http";
import { serializeProgress } from "@/lib/serialize";

export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> }
) {
  try {
    const userId = requireUserId(request);
    const { id } = await params;
    const doc = await prisma.document.findFirst({ where: { id, ownerId: userId } });
    if (!doc) return jsonError(404, "not_found");

    const requestedFormat = request.nextUrl.searchParams.get("format");
    const parsedFormat = formatSchema.safeParse(requestedFormat ?? (doc.epubStatus === "ready" ? "epub" : "pdf"));
    if (!parsedFormat.success) return jsonError(400, "invalid_format");
    const progress = await prisma.readingProgress.findUnique({
      where: { documentId_ownerId_format: { documentId: doc.id, ownerId: userId, format: parsedFormat.data } },
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

    const parsed = progressSchema.safeParse(await request.json().catch(() => null));
    if (!parsed.success) return jsonError(400, "invalid_body");
    const format = parsed.data.format ?? (doc.epubStatus === "ready" ? "epub" : "pdf");

    const progress = await prisma.readingProgress.upsert({
      where: { documentId_ownerId_format: { documentId: doc.id, ownerId: userId, format } },
      create: { documentId: doc.id, ownerId: userId, page: parsed.data.page, format },
      update: { page: parsed.data.page },
    });

    return NextResponse.json(serializeProgress(progress));
  } catch (err) {
    if (err instanceof AuthError) return jsonError(401, "unauthorized");
    throw err;
  }
}

import { NextRequest, NextResponse } from "next/server";
import { prisma } from "@/lib/prisma";
import { getObjectStream } from "@/lib/storage";
import { AuthError, jsonError, requireUserId } from "@/lib/http";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

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
    if (!doc.s3Key) return jsonError(404, "pdf_not_available");

    const range = request.headers.get("range") ?? undefined;
    const result = await getObjectStream(doc.s3Key, range);
    if (!result.Body) return jsonError(500, "storage_error");

    const webStream = result.Body.transformToWebStream();
    const isPartial = Boolean(range && result.ContentRange);

    const headers = new Headers({
      "Content-Type": "application/pdf",
      "Accept-Ranges": "bytes",
      "Content-Disposition": `inline; filename="${doc.filename.replace(/"/g, "")}"`,
    });
    if (result.ContentLength != null) headers.set("Content-Length", String(result.ContentLength));
    if (result.ContentRange) headers.set("Content-Range", result.ContentRange);

    return new NextResponse(webStream, { status: isPartial ? 206 : 200, headers });
  } catch (err) {
    if (err instanceof AuthError) return jsonError(401, "unauthorized");
    throw err;
  }
}

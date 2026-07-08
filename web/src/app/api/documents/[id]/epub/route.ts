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
    if (!doc.epubKey || doc.epubStatus !== "ready") return jsonError(404, "epub_not_available");

    const result = await getObjectStream(doc.epubKey);
    if (!result.Body) return jsonError(500, "storage_error");

    const webStream = result.Body.transformToWebStream();
    const filename = doc.filename.replace(/\.pdf$/i, "").replace(/"/g, "") + ".epub";

    const headers = new Headers({
      "Content-Type": "application/epub+zip",
      "Content-Disposition": `attachment; filename="${filename}"`,
    });
    if (result.ContentLength != null) headers.set("Content-Length", String(result.ContentLength));

    return new NextResponse(webStream, { status: 200, headers });
  } catch (err) {
    if (err instanceof AuthError) return jsonError(401, "unauthorized");
    throw err;
  }
}

import { randomUUID, createHash } from "node:crypto";
import { Readable } from "node:stream";
import { NextRequest, NextResponse } from "next/server";
import { prisma } from "@/lib/prisma";
import { ensureBucket, putObject } from "@/lib/storage";
import { AuthError, jsonError, requireUserId } from "@/lib/http";
import { serializeDocument } from "@/lib/serialize";
import { convertPdfToEpub } from "@/lib/epub";

export const runtime = "nodejs";

export async function GET(request: NextRequest) {
  try {
    const userId = requireUserId(request);
    const documents = await prisma.document.findMany({
      where: { ownerId: userId, deletedAt: null },
      orderBy: { createdAt: "desc" },
    });
    return NextResponse.json({ documents: documents.map(serializeDocument) });
  } catch (err) {
    if (err instanceof AuthError) return jsonError(401, "unauthorized");
    throw err;
  }
}

export async function POST(request: NextRequest) {
  try {
    const userId = requireUserId(request);

    const formData = await request.formData();
    const file = formData.get("file");
    const titleField = formData.get("title");

    if (!(file instanceof File)) return jsonError(400, "missing_file");

    const lowerName = file.name.toLowerCase();
    const isPdf = file.type === "application/pdf" || lowerName.endsWith(".pdf");
    const isEpub = file.type === "application/epub+zip" || lowerName.endsWith(".epub");
    if (!isPdf && !isEpub) return jsonError(400, "unsupported_format");

    const buffer = Buffer.from(await file.arrayBuffer());
    const checksum = createHash("sha256").update(buffer).digest("hex");
    const title = typeof titleField === "string" && titleField.trim() ? titleField.trim() : file.name;

    await ensureBucket();

    let s3Key: string | undefined;
    let epubKey: string | undefined;
    let epubStatus: string | undefined;

    if (isPdf) {
      s3Key = `${userId}/${randomUUID()}.pdf`;
      await putObject(s3Key, Readable.from(buffer), "application/pdf");

      // PDFs are always converted to EPUB too, so the same book can be read
      // either way; a failed conversion (e.g. scanned/graphic-only PDFs)
      // just leaves the EPUB side unavailable, it doesn't fail the upload.
      try {
        const epubBuffer = await convertPdfToEpub(buffer, title);
        epubKey = `${userId}/${randomUUID()}.epub`;
        await putObject(epubKey, Readable.from(epubBuffer), "application/epub+zip");
        epubStatus = "ready";
      } catch {
        epubStatus = "failed";
      }
    } else {
      epubKey = `${userId}/${randomUUID()}.epub`;
      await putObject(epubKey, Readable.from(buffer), "application/epub+zip");
      epubStatus = "ready";
    }

    const document = await prisma.document.create({
      data: {
        ownerId: userId,
        title,
        filename: file.name,
        s3Key,
        sizeBytes: buffer.length,
        checksum,
        epubKey,
        epubStatus,
      },
    });

    return NextResponse.json(serializeDocument(document), { status: 201 });
  } catch (err) {
    if (err instanceof AuthError) return jsonError(401, "unauthorized");
    throw err;
  }
}

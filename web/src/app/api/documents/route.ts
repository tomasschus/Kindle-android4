import { randomUUID, createHash } from "node:crypto";
import { Readable } from "node:stream";
import { NextRequest, NextResponse } from "next/server";
import { prisma } from "@/lib/prisma";
import { ensureBucket, putObject } from "@/lib/storage";
import { AuthError, jsonError, requireUserId } from "@/lib/http";
import { serializeDocument } from "@/lib/serialize";

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
    if (file.type && file.type !== "application/pdf" && !file.name.toLowerCase().endsWith(".pdf")) {
      return jsonError(400, "not_a_pdf");
    }

    const buffer = Buffer.from(await file.arrayBuffer());
    const checksum = createHash("sha256").update(buffer).digest("hex");
    const key = `${userId}/${randomUUID()}.pdf`;

    await ensureBucket();
    await putObject(key, Readable.from(buffer), "application/pdf");

    const document = await prisma.document.create({
      data: {
        ownerId: userId,
        title: typeof titleField === "string" && titleField.trim() ? titleField.trim() : file.name,
        filename: file.name,
        s3Key: key,
        sizeBytes: buffer.length,
        checksum,
      },
    });

    return NextResponse.json(serializeDocument(document), { status: 201 });
  } catch (err) {
    if (err instanceof AuthError) return jsonError(401, "unauthorized");
    throw err;
  }
}

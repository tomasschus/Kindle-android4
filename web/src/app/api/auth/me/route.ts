import { NextRequest, NextResponse } from "next/server";
import { prisma } from "@/lib/prisma";
import { AuthError, jsonError, requireUserId } from "@/lib/http";

export async function GET(request: NextRequest) {
  try {
    const userId = requireUserId(request);
    const user = await prisma.user.findUnique({ where: { id: userId } });
    if (!user) return jsonError(401, "unauthorized");
    return NextResponse.json({ user: { id: user.id, username: user.username } });
  } catch (err) {
    if (err instanceof AuthError) return jsonError(401, "unauthorized");
    throw err;
  }
}

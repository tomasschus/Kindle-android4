import { NextRequest, NextResponse } from "next/server";
import { z } from "zod";
import { cookies } from "next/headers";
import { prisma } from "@/lib/prisma";
import { hashPassword, signToken, SESSION_COOKIE } from "@/lib/auth";
import { jsonError } from "@/lib/http";

const bodySchema = z.object({
  username: z.string().min(3).max(64),
  password: z.string().min(8).max(256),
});

/**
 * Self-service signup is intentionally narrow: it's meant to bootstrap the
 * very first account on a freshly deployed instance. After that it's gated
 * behind ALLOW_SIGNUP so a personal single-user server doesn't accidentally
 * accept public registrations.
 */
export async function POST(request: NextRequest) {
  const userCount = await prisma.user.count();
  const signupAllowed = userCount === 0 || process.env.ALLOW_SIGNUP === "true";
  if (!signupAllowed) return jsonError(403, "signup_disabled");

  const parsed = bodySchema.safeParse(await request.json().catch(() => null));
  if (!parsed.success) return jsonError(400, "invalid_body");

  const { username, password } = parsed.data;
  const existing = await prisma.user.findUnique({ where: { username } });
  if (existing) return jsonError(409, "username_taken");

  const user = await prisma.user.create({
    data: { username, passwordHash: await hashPassword(password) },
  });

  const token = signToken(user.id);
  const cookieStore = await cookies();
  cookieStore.set(SESSION_COOKIE, token, {
    httpOnly: true,
    sameSite: "lax",
    secure: process.env.NODE_ENV === "production",
    path: "/",
    maxAge: 90 * 24 * 60 * 60,
  });

  return NextResponse.json(
    { token, user: { id: user.id, username: user.username } },
    { status: 201 }
  );
}

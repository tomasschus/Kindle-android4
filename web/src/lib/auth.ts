import bcrypt from "bcryptjs";
import jwt from "jsonwebtoken";
import { NextRequest } from "next/server";

const JWT_SECRET = process.env.JWT_SECRET;
const TOKEN_TTL_SECONDS = 90 * 24 * 60 * 60; // 90 days, matches docs/API.md
export const SESSION_COOKIE = "kindle_session";

function secret(): string {
  if (!JWT_SECRET) {
    throw new Error("JWT_SECRET is not set");
  }
  return JWT_SECRET;
}

export async function hashPassword(password: string): Promise<string> {
  return bcrypt.hash(password, 10);
}

export async function verifyPassword(password: string, hash: string): Promise<boolean> {
  return bcrypt.compare(password, hash);
}

export function signToken(userId: string): string {
  return jwt.sign({ sub: userId }, secret(), { expiresIn: TOKEN_TTL_SECONDS });
}

export function verifyToken(token: string): { userId: string } | null {
  try {
    const payload = jwt.verify(token, secret());
    if (typeof payload === "object" && payload !== null && typeof payload.sub === "string") {
      return { userId: payload.sub };
    }
    return null;
  } catch {
    return null;
  }
}

/**
 * Resolves the authenticated user id for a request. Accepts either an
 * `Authorization: Bearer <token>` header (Android app / API clients) or the
 * web session cookie (browser).
 */
export function getUserIdFromRequest(request: NextRequest): string | null {
  const authHeader = request.headers.get("authorization");
  if (authHeader?.startsWith("Bearer ")) {
    const token = authHeader.slice("Bearer ".length).trim();
    const result = verifyToken(token);
    if (result) return result.userId;
  }

  const cookieToken = request.cookies.get(SESSION_COOKIE)?.value;
  if (cookieToken) {
    const result = verifyToken(cookieToken);
    if (result) return result.userId;
  }

  return null;
}

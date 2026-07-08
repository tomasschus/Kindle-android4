import { NextRequest, NextResponse } from "next/server";
import { getUserIdFromRequest } from "@/lib/auth";

export function jsonError(status: number, error: string, message?: string) {
  return NextResponse.json({ error, ...(message ? { message } : {}) }, { status });
}

export class AuthError extends Error {}

/** Returns the authenticated user id, or throws AuthError to be caught by the route. */
export function requireUserId(request: NextRequest): string {
  const userId = getUserIdFromRequest(request);
  if (!userId) throw new AuthError("unauthorized");
  return userId;
}

import { redirect } from "next/navigation";
import { cookies } from "next/headers";
import { SESSION_COOKIE, verifyToken } from "@/lib/auth";
import { prisma } from "@/lib/prisma";
import { Dashboard } from "@/components/Dashboard";

export default async function DashboardPage() {
  const cookieStore = await cookies();
  const token = cookieStore.get(SESSION_COOKIE)?.value;
  const session = token ? verifyToken(token) : null;
  if (!session) redirect("/login");

  const user = await prisma.user.findUnique({ where: { id: session.userId } });
  if (!user) redirect("/login");

  return <Dashboard username={user.username} />;
}

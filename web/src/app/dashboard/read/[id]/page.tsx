import { notFound, redirect } from "next/navigation";
import { cookies } from "next/headers";
import { SESSION_COOKIE, verifyToken } from "@/lib/auth";
import { prisma } from "@/lib/prisma";
import { EpubReader } from "@/components/EpubReader";

export default async function ReadEpubPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const cookieStore = await cookies();
  const token = cookieStore.get(SESSION_COOKIE)?.value;
  const session = token ? verifyToken(token) : null;
  if (!session) redirect("/login");

  const doc = await prisma.document.findFirst({
    where: { id, ownerId: session.userId, deletedAt: null },
  });
  if (!doc || doc.epubStatus !== "ready") notFound();

  return <EpubReader documentId={doc.id} title={doc.title} />;
}

"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { UploadModal } from "@/components/UploadModal";

type DocumentDto = {
  id: string;
  title: string;
  filename: string;
  sizeBytes: number;
  pageCount: number | null;
  checksum: string;
  createdAt: string;
  updatedAt: string;
};

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const units = ["KB", "MB", "GB"];
  let value = bytes / 1024;
  let unitIndex = 0;
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024;
    unitIndex += 1;
  }
  return `${value.toFixed(1)} ${units[unitIndex]}`;
}

function BookIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" className="shrink-0 text-primary dark:text-blue-300">
      <path
        d="M5 4.5A1.5 1.5 0 016.5 3H17a2 2 0 012 2v14a2 2 0 01-2 2H6.5A1.5 1.5 0 015 19.5v-15z"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinejoin="round"
      />
      <path d="M8 3v18" stroke="currentColor" strokeWidth="1.6" />
    </svg>
  );
}

export function Dashboard({ username }: { username: string }) {
  const router = useRouter();
  const [documents, setDocuments] = useState<DocumentDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [deletingId, setDeletingId] = useState<string | null>(null);

  useEffect(() => {
    let ignore = false;
    async function load() {
      try {
        const res = await fetch("/api/documents");
        if (res.status === 401) {
          router.push("/login");
          return;
        }
        const body = await res.json();
        if (!ignore) setDocuments(body.documents);
      } catch {
        if (!ignore) setError("No se pudieron cargar los documentos.");
      }
    }
    load();
    return () => {
      ignore = true;
    };
  }, [router]);

  useEffect(() => {
    if (!notice) return;
    const t = setTimeout(() => setNotice(null), 4000);
    return () => clearTimeout(t);
  }, [notice]);

  function onUploaded(doc: DocumentDto) {
    setDocuments((docs) => (docs ? [doc, ...docs] : [doc]));
    setModalOpen(false);
    setNotice(`"${doc.title}" se subió correctamente.`);
  }

  async function onDelete(id: string) {
    if (!confirm("¿Eliminar este PDF? Esta acción no se puede deshacer.")) return;
    setDeletingId(id);
    try {
      const res = await fetch(`/api/documents/${id}`, { method: "DELETE" });
      if (res.ok || res.status === 404) {
        setDocuments((docs) => docs?.filter((d) => d.id !== id) ?? null);
      } else {
        setError("No se pudo eliminar el documento.");
      }
    } finally {
      setDeletingId(null);
    }
  }

  async function onLogout() {
    await fetch("/api/auth/logout", { method: "POST" });
    router.push("/login");
    router.refresh();
  }

  return (
    <div className="min-h-full flex-1 bg-blue-50 dark:bg-slate-950">
      <div className="mx-auto flex w-full max-w-3xl flex-col gap-6 px-4 py-10">
        <header className="flex items-center justify-between">
          <div>
            <h1 className="text-xl font-semibold text-primary dark:text-blue-300">Biblioteca PDF</h1>
            <p className="text-sm text-zinc-500 dark:text-zinc-400">Conectado como {username}</p>
          </div>
          <div className="flex items-center gap-3">
            <button
              onClick={() => setModalOpen(true)}
              className="flex items-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-primary-hover"
            >
              <svg width="16" height="16" viewBox="0 0 20 20" fill="none">
                <path d="M10 4v12M4 10h12" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
              </svg>
              Subir nuevo libro
            </button>
            <button
              onClick={onLogout}
              className="rounded-lg border border-blue-200 px-3 py-1.5 text-sm text-primary hover:bg-blue-100 dark:border-slate-700 dark:text-blue-300 dark:hover:bg-slate-900"
            >
              Cerrar sesión
            </button>
          </div>
        </header>

        <h2 className="text-sm font-medium text-zinc-600 dark:text-zinc-400">
          {documents ? `${documents.length} libro${documents.length === 1 ? "" : "s"}` : "Tu biblioteca"}
        </h2>

        {notice && (
          <p className="rounded-lg border border-green-200 bg-green-50 px-4 py-2 text-sm text-green-700 dark:border-green-900 dark:bg-green-950 dark:text-green-300">
            {notice}
          </p>
        )}
        {error && <p className="text-sm text-red-600 dark:text-red-400">{error}</p>}

        {documents === null && (
          <div className="flex flex-col items-center gap-3 rounded-2xl border border-blue-100 bg-white px-6 py-16 text-center dark:border-slate-800 dark:bg-slate-900">
            <div className="h-6 w-6 animate-spin rounded-full border-2 border-blue-200 border-t-primary dark:border-slate-700 dark:border-t-blue-300" />
            <p className="text-sm text-zinc-500 dark:text-zinc-400">Cargando tu biblioteca…</p>
          </div>
        )}

        {documents?.length === 0 && (
          <div className="flex flex-col items-center gap-3 rounded-2xl border border-dashed border-blue-200 bg-white px-6 py-16 text-center dark:border-slate-700 dark:bg-slate-900">
            <div className="rounded-full bg-blue-50 p-3 dark:bg-slate-800">
              <BookIcon />
            </div>
            <p className="font-medium text-zinc-900 dark:text-zinc-50">Todavía no subiste ningún libro</p>
            <p className="max-w-sm text-sm text-zinc-500 dark:text-zinc-400">
              Los PDFs que subas van a estar disponibles para leer y sincronizar en todos tus dispositivos.
            </p>
            <button
              onClick={() => setModalOpen(true)}
              className="mt-2 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-primary-hover"
            >
              Subir tu primer libro
            </button>
          </div>
        )}

        {documents && documents.length > 0 && (
          <section className="flex flex-col gap-3">
            {documents.map((doc) => (
              <div
                key={doc.id}
                className="flex items-center justify-between gap-4 rounded-xl border border-blue-100 bg-white px-4 py-3 dark:border-slate-800 dark:bg-slate-900"
              >
                <div className="flex min-w-0 items-center gap-3">
                  <BookIcon />
                  <div className="min-w-0">
                    <p className="truncate font-medium text-zinc-900 dark:text-zinc-50">{doc.title}</p>
                    <p className="text-xs text-zinc-500 dark:text-zinc-400">
                      {formatBytes(doc.sizeBytes)} · {new Date(doc.createdAt).toLocaleDateString()}
                    </p>
                  </div>
                </div>
                <div className="flex shrink-0 items-center gap-3">
                  <a
                    href={`/api/documents/${doc.id}/download`}
                    className="text-sm text-primary underline-offset-2 hover:underline dark:text-blue-300"
                    target="_blank"
                    rel="noreferrer"
                  >
                    Ver
                  </a>
                  <button
                    onClick={() => onDelete(doc.id)}
                    disabled={deletingId === doc.id}
                    className="text-sm text-red-600 hover:underline disabled:opacity-50 dark:text-red-400"
                  >
                    {deletingId === doc.id ? "Eliminando…" : "Eliminar"}
                  </button>
                </div>
              </div>
            ))}
          </section>
        )}
      </div>

      {modalOpen && (
        <UploadModal onClose={() => setModalOpen(false)} onUploaded={onUploaded} />
      )}
    </div>
  );
}

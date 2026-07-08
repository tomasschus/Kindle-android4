"use client";

import { useCallback, useEffect, useRef, useState, FormEvent } from "react";
import { useRouter } from "next/navigation";

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

export function Dashboard({ username }: { username: string }) {
  const router = useRouter();
  const [documents, setDocuments] = useState<DocumentDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);
  const [uploadTitle, setUploadTitle] = useState("");
  const fileInputRef = useRef<HTMLInputElement>(null);

  const loadDocuments = useCallback(async () => {
    const res = await fetch("/api/documents");
    if (res.status === 401) {
      router.push("/login");
      return;
    }
    const body = await res.json();
    setDocuments(body.documents);
  }, [router]);

  useEffect(() => {
    let ignore = false;
    (async () => {
      try {
        const res = await fetch("/api/documents");
        if (res.status === 401) {
          router.push("/login");
          return;
        }
        const body = await res.json();
        if (ignore) return;
        setDocuments(body.documents);
      } catch {
        if (!ignore) setError("No se pudieron cargar los documentos.");
      }
    })();
    return () => {
      ignore = true;
    };
  }, [router]);

  async function onUpload(e: FormEvent) {
    e.preventDefault();
    setError(null);
    const file = fileInputRef.current?.files?.[0];
    if (!file) {
      setError("Elegí un archivo PDF.");
      return;
    }
    setUploading(true);
    try {
      const formData = new FormData();
      formData.append("file", file);
      if (uploadTitle.trim()) formData.append("title", uploadTitle.trim());

      const res = await fetch("/api/documents", { method: "POST", body: formData });
      if (!res.ok) {
        setError("No se pudo subir el archivo.");
        return;
      }
      setUploadTitle("");
      if (fileInputRef.current) fileInputRef.current.value = "";
      await loadDocuments();
    } finally {
      setUploading(false);
    }
  }

  async function onDelete(id: string) {
    if (!confirm("¿Eliminar este PDF? Esta acción no se puede deshacer.")) return;
    const res = await fetch(`/api/documents/${id}`, { method: "DELETE" });
    if (res.ok || res.status === 404) {
      setDocuments((docs) => docs?.filter((d) => d.id !== id) ?? null);
    } else {
      setError("No se pudo eliminar el documento.");
    }
  }

  async function onLogout() {
    await fetch("/api/auth/logout", { method: "POST" });
    router.push("/login");
    router.refresh();
  }

  return (
    <div className="min-h-full flex-1 bg-blue-50 dark:bg-slate-950">
      <div className="mx-auto flex w-full max-w-3xl flex-col gap-8 px-4 py-10">
        <header className="flex items-center justify-between">
          <div>
            <h1 className="text-xl font-semibold text-primary dark:text-blue-300">Kindle Casero</h1>
            <p className="text-sm text-zinc-500 dark:text-zinc-400">Conectado como {username}</p>
          </div>
          <button
            onClick={onLogout}
            className="rounded-lg border border-blue-200 px-3 py-1.5 text-sm text-primary hover:bg-blue-100 dark:border-slate-700 dark:text-blue-300 dark:hover:bg-slate-900"
          >
            Cerrar sesión
          </button>
        </header>

        <form
          onSubmit={onUpload}
          className="flex flex-col gap-3 rounded-2xl border border-blue-100 bg-white p-6 dark:border-slate-800 dark:bg-slate-900 sm:flex-row sm:items-end"
        >
          <label className="flex flex-1 flex-col gap-1 text-sm text-zinc-700 dark:text-zinc-300">
            Título (opcional)
            <input
              className="rounded-lg border border-zinc-300 px-3 py-2 text-base outline-none focus:border-primary focus:ring-1 focus:ring-primary dark:border-zinc-700 dark:bg-zinc-900"
              value={uploadTitle}
              onChange={(e) => setUploadTitle(e.target.value)}
              placeholder="Se usa el nombre del archivo si lo dejás vacío"
            />
          </label>
          <label className="flex flex-col gap-1 text-sm text-zinc-700 dark:text-zinc-300">
            Archivo PDF
            <input ref={fileInputRef} type="file" accept="application/pdf" className="text-sm" />
          </label>
          <button
            type="submit"
            disabled={uploading}
            className="h-fit rounded-lg bg-primary px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-primary-hover disabled:opacity-50"
          >
            {uploading ? "Subiendo…" : "Subir"}
          </button>
        </form>

        {error && <p className="text-sm text-red-600 dark:text-red-400">{error}</p>}

        <section className="flex flex-col gap-3">
          {documents === null && <p className="text-sm text-zinc-500">Cargando…</p>}
          {documents?.length === 0 && (
            <p className="text-sm text-zinc-500">
              Todavía no subiste ningún PDF. Los que subas acá aparecerán para sincronizar en la tablet.
            </p>
          )}
          {documents?.map((doc) => (
            <div
              key={doc.id}
              className="flex items-center justify-between gap-4 rounded-xl border border-blue-100 bg-white px-4 py-3 dark:border-slate-800 dark:bg-slate-900"
            >
              <div className="min-w-0">
                <p className="truncate font-medium text-zinc-900 dark:text-zinc-50">{doc.title}</p>
                <p className="text-xs text-zinc-500 dark:text-zinc-400">
                  {formatBytes(doc.sizeBytes)} · {new Date(doc.createdAt).toLocaleDateString()}
                </p>
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
                  className="text-sm text-red-600 hover:underline dark:text-red-400"
                >
                  Eliminar
                </button>
              </div>
            </div>
          ))}
        </section>
      </div>
    </div>
  );
}

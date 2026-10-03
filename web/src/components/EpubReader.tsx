"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import type { Book as EpubBook, Rendition as EpubRendition } from "epubjs";

type RelocatedEvent = { start: { index: number } };

export function EpubReader({ documentId, title }: { documentId: string; title: string }) {
  const viewerRef = useRef<HTMLDivElement>(null);
  const renditionRef = useRef<EpubRendition | null>(null);
  const [status, setStatus] = useState<"loading" | "ready" | "error">("loading");
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    let book: EpubBook | null = null;

    (async () => {
      try {
        const [epubRes, progressRes] = await Promise.all([
          fetch(`/api/documents/${documentId}/epub`),
          fetch(`/api/documents/${documentId}/progress?format=epub`),
        ]);
        if (!epubRes.ok) throw new Error("No se pudo descargar el EPUB.");
        const buffer = await epubRes.arrayBuffer();
        const progress = progressRes.ok ? await progressRes.json() : null;

        if (cancelled || !viewerRef.current) return;

        const ePub = (await import("epubjs")).default;
        book = ePub(buffer);
        const rendition = book.renderTo(viewerRef.current, {
          width: "100%",
          height: "100%",
          flow: "scrolled-doc",
        });
        renditionRef.current = rendition;

        rendition.themes.default({
          body: { background: "#ffffff", color: "#1a1a1a", padding: "0 1rem" },
        });

        rendition.on("relocated", (location: RelocatedEvent) => {
          fetch(`/api/documents/${documentId}/progress`, {
            method: "PUT",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ page: location.start.index, format: "epub" }),
          }).catch(() => {});
        });

        const startAt =
          progress && typeof progress.page === "number" && progress.page > 0 ? progress.page : undefined;
        await rendition.display(startAt);

        if (!cancelled) setStatus("ready");
      } catch (err) {
        if (!cancelled) {
          setErrorMessage(err instanceof Error ? err.message : "No se pudo abrir el EPUB.");
          setStatus("error");
        }
      }
    })();

    return () => {
      cancelled = true;
      renditionRef.current?.destroy();
      renditionRef.current = null;
      book?.destroy();
    };
  }, [documentId]);

  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "ArrowRight") renditionRef.current?.next();
      if (e.key === "ArrowLeft") renditionRef.current?.prev();
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, []);

  return (
    <div className="flex h-dvh flex-col bg-blue-50 dark:bg-slate-950">
      <header className="flex items-center justify-between gap-3 border-b border-blue-100 bg-white px-4 py-3 dark:border-slate-800 dark:bg-slate-900">
        <Link
          href="/dashboard"
          className="shrink-0 text-sm text-primary underline-offset-2 hover:underline dark:text-blue-300"
        >
          ← Volver
        </Link>
        <h1 className="truncate text-sm font-medium text-zinc-900 dark:text-zinc-50">{title}</h1>
        <div className="w-12 shrink-0" />
      </header>

      <div className="relative flex-1 overflow-hidden">
        {status === "loading" && (
          <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 bg-white dark:bg-slate-950">
            <div className="h-6 w-6 animate-spin rounded-full border-2 border-blue-200 border-t-primary dark:border-slate-700 dark:border-t-blue-300" />
            <p className="text-sm text-zinc-500 dark:text-zinc-400">Abriendo libro…</p>
          </div>
        )}
        {status === "error" && (
          <div className="absolute inset-0 flex flex-col items-center justify-center gap-2 bg-white px-6 text-center dark:bg-slate-950">
            <p className="text-sm text-red-600 dark:text-red-400">{errorMessage}</p>
            <Link href="/dashboard" className="text-sm text-primary underline-offset-2 hover:underline dark:text-blue-300">
              Volver a la biblioteca
            </Link>
          </div>
        )}
        <div ref={viewerRef} className="h-full w-full" />
      </div>

      {status === "ready" && (
        <footer className="flex items-center justify-between border-t border-blue-100 bg-white px-4 py-3 dark:border-slate-800 dark:bg-slate-900">
          <button
            onClick={() => renditionRef.current?.prev()}
            className="rounded-lg border border-blue-200 px-4 py-2 text-sm text-primary hover:bg-blue-50 dark:border-slate-700 dark:text-blue-300 dark:hover:bg-slate-800"
          >
            ← Anterior
          </button>
          <button
            onClick={() => renditionRef.current?.next()}
            className="rounded-lg border border-blue-200 px-4 py-2 text-sm text-primary hover:bg-blue-50 dark:border-slate-700 dark:text-blue-300 dark:hover:bg-slate-800"
          >
            Siguiente →
          </button>
        </footer>
      )}
    </div>
  );
}

"use client";

import { useCallback, useEffect, useRef, useState } from "react";

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

function isPdf(file: File): boolean {
  return file.type === "application/pdf" || file.name.toLowerCase().endsWith(".pdf");
}

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

function uploadErrorMessage(code: string): string {
  switch (code) {
    case "not_a_pdf":
      return "Ese archivo no es un PDF. Elegí un archivo con extensión .pdf.";
    case "missing_file":
      return "No se recibió ningún archivo. Probá de nuevo.";
    case "unauthorized":
      return "Tu sesión expiró. Recargá la página e iniciá sesión de nuevo.";
    case "network_error":
      return "No se pudo conectar con el servidor. Revisá tu conexión e intentá de nuevo.";
    default:
      return "No se pudo subir el archivo. Intentá de nuevo.";
  }
}

function uploadWithProgress(file: File, title: string, onProgress: (pct: number) => void): Promise<DocumentDto> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open("POST", "/api/documents");
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) onProgress(Math.round((e.loaded / e.total) * 100));
    };
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        try {
          resolve(JSON.parse(xhr.responseText));
        } catch {
          reject(new Error("bad_response"));
        }
      } else {
        let code = "upload_failed";
        try {
          code = JSON.parse(xhr.responseText).error ?? code;
        } catch {
          // non-JSON error body, keep generic code
        }
        reject(new Error(code));
      }
    };
    xhr.onerror = () => reject(new Error("network_error"));

    const formData = new FormData();
    formData.append("file", file);
    if (title.trim()) formData.append("title", title.trim());
    xhr.send(formData);
  });
}

export function UploadModal({
  onClose,
  onUploaded,
}: {
  onClose: () => void;
  onUploaded: (doc: DocumentDto) => void;
}) {
  const [file, setFile] = useState<File | null>(null);
  const [title, setTitle] = useState("");
  const [dragActive, setDragActive] = useState(false);
  const [progress, setProgress] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const uploading = progress !== null;

  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape" && !uploading) onClose();
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [onClose, uploading]);

  const chooseFile = useCallback((f: File) => {
    if (!isPdf(f)) {
      setError("Ese archivo no es un PDF. Elegí un archivo con extensión .pdf.");
      return;
    }
    setError(null);
    setFile(f);
    if (!title.trim()) setTitle(f.name.replace(/\.pdf$/i, ""));
  }, [title]);

  function onDrop(e: React.DragEvent) {
    e.preventDefault();
    setDragActive(false);
    if (uploading) return;
    const dropped = e.dataTransfer.files?.[0];
    if (dropped) chooseFile(dropped);
  }

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!file) {
      setError("Elegí un archivo PDF para subir.");
      return;
    }
    setError(null);
    setProgress(0);
    try {
      const doc = await uploadWithProgress(file, title, setProgress);
      onUploaded(doc);
    } catch (err) {
      setError(uploadErrorMessage(err instanceof Error ? err.message : "upload_failed"));
      setProgress(null);
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
      onClick={() => !uploading && onClose()}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="upload-modal-title"
        className="w-full max-w-md rounded-2xl bg-white p-6 shadow-xl dark:bg-slate-900"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between">
          <h2 id="upload-modal-title" className="text-lg font-semibold text-zinc-900 dark:text-zinc-50">
            Subir un nuevo libro
          </h2>
          <button
            type="button"
            onClick={onClose}
            disabled={uploading}
            aria-label="Cerrar"
            className="rounded-lg p-1 text-zinc-400 hover:bg-zinc-100 hover:text-zinc-600 disabled:opacity-40 dark:hover:bg-slate-800"
          >
            <svg width="20" height="20" viewBox="0 0 20 20" fill="none">
              <path d="M5 5l10 10M15 5L5 15" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
            </svg>
          </button>
        </div>

        <ol className="mt-4 flex flex-col gap-1.5 text-sm text-zinc-600 dark:text-zinc-400">
          <li>1. Arrastrá tu archivo PDF a la zona de abajo, o hacé clic para elegirlo.</li>
          <li>2. Revisá el título (podés cambiarlo si querés).</li>
          <li>3. Tocá &quot;Subir&quot; y esperá a que termine la barra de progreso.</li>
        </ol>

        <form onSubmit={onSubmit} className="mt-4 flex flex-col gap-4">
          <label
            onDragOver={(e) => {
              e.preventDefault();
              if (!uploading) setDragActive(true);
            }}
            onDragLeave={() => setDragActive(false)}
            onDrop={onDrop}
            className={`flex cursor-pointer flex-col items-center justify-center gap-2 rounded-xl border-2 border-dashed px-4 py-8 text-center transition-colors ${
              dragActive
                ? "border-primary bg-blue-50 dark:bg-slate-800"
                : "border-zinc-300 hover:border-primary/60 dark:border-zinc-700"
            } ${uploading ? "pointer-events-none opacity-60" : ""}`}
          >
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none" className="text-primary dark:text-blue-300">
              <path
                d="M12 16V4m0 0L7 9m5-5l5 5M5 16v2a2 2 0 002 2h10a2 2 0 002-2v-2"
                stroke="currentColor"
                strokeWidth="1.7"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
            {file ? (
              <p className="text-sm font-medium text-zinc-900 dark:text-zinc-50">
                {file.name} · {formatBytes(file.size)}
              </p>
            ) : (
              <>
                <p className="text-sm font-medium text-zinc-900 dark:text-zinc-50">
                  Arrastrá tu PDF acá
                </p>
                <p className="text-xs text-zinc-500 dark:text-zinc-400">o hacé clic para elegirlo desde tu computadora</p>
              </>
            )}
            <input
              ref={fileInputRef}
              type="file"
              accept="application/pdf"
              className="sr-only"
              disabled={uploading}
              onChange={(e) => {
                const picked = e.target.files?.[0];
                if (picked) chooseFile(picked);
              }}
            />
          </label>

          <label className="flex flex-col gap-1 text-sm text-zinc-700 dark:text-zinc-300">
            Título
            <input
              className="rounded-lg border border-zinc-300 px-3 py-2 text-base outline-none focus:border-primary focus:ring-1 focus:ring-primary disabled:opacity-60 dark:border-zinc-700 dark:bg-zinc-900"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="Se usa el nombre del archivo si lo dejás vacío"
              disabled={uploading}
            />
          </label>

          {progress !== null && (
            <div className="flex flex-col gap-1">
              <div className="h-2 w-full overflow-hidden rounded-full bg-zinc-200 dark:bg-slate-800">
                <div
                  className="h-full rounded-full bg-primary transition-all duration-150"
                  style={{ width: `${progress}%` }}
                />
              </div>
              <p className="text-xs text-zinc-500 dark:text-zinc-400">Subiendo… {progress}%</p>
            </div>
          )}

          {error && <p className="text-sm text-red-600 dark:text-red-400">{error}</p>}

          <div className="mt-1 flex justify-end gap-3">
            <button
              type="button"
              onClick={onClose}
              disabled={uploading}
              className="rounded-lg px-4 py-2 text-sm font-medium text-zinc-600 hover:bg-zinc-100 disabled:opacity-40 dark:text-zinc-300 dark:hover:bg-slate-800"
            >
              Cancelar
            </button>
            <button
              type="submit"
              disabled={uploading || !file}
              className="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-primary-hover disabled:opacity-50"
            >
              {uploading ? `Subiendo… ${progress}%` : "Subir"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

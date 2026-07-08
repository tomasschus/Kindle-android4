import { PDFParse } from "pdf-parse";
import epubGenMemory from "epub-gen-memory";

// epub-gen-memory's CJS build double-wraps its default export under some
// bundler/ESM interop combinations (import gives {default: <fn>} instead of
// the function itself) — unwrap defensively rather than assume either shape.
const epub: typeof epubGenMemory =
  typeof epubGenMemory === "function"
    ? epubGenMemory
    : (epubGenMemory as unknown as { default: typeof epubGenMemory }).default;

function escapeHtml(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

/**
 * Best-effort PDF -> EPUB conversion: extracts the PDF's text layer and
 * reflows it into a single-chapter EPUB. Layout/images are not preserved —
 * this is meant for text-heavy books, not scanned/graphic PDFs.
 */
export async function convertPdfToEpub(pdfBuffer: Buffer, title: string): Promise<Buffer> {
  const parser = new PDFParse({ data: pdfBuffer });
  let text: string;
  try {
    const result = await parser.getText();
    text = result.text ?? "";
  } finally {
    await parser.destroy();
  }

  const paragraphs = text
    .split(/\n{2,}/)
    .map((p) => p.replace(/\s+/g, " ").trim())
    .filter(Boolean);

  const html = paragraphs.length
    ? paragraphs.map((p) => `<p>${escapeHtml(p)}</p>`).join("\n")
    : "<p>No se pudo extraer texto de este PDF.</p>";

  return epub({ title, tocTitle: "Contenido" }, [{ title, content: html }]);
}

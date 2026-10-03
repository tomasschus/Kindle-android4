import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { PDFParse } from "pdf-parse";
import epubGenMemory from "epub-gen-memory";
import { createWorker } from "tesseract.js";

// epub-gen-memory's CJS build double-wraps its default export under some
// bundler/ESM interop combinations (import gives {default: <fn>} instead of
// the function itself) — unwrap defensively rather than assume either shape.
const epub: typeof epubGenMemory =
  typeof epubGenMemory === "function"
    ? epubGenMemory
    : (epubGenMemory as unknown as { default: typeof epubGenMemory }).default;

// Languages loaded for OCR, tried together against every scanned page.
// Covers the languages we've actually seen show up in documents so far.
const OCR_LANGUAGES = "eng+spa+por";
// Scale applied before rendering a page for OCR: higher = sharper text but
// slower/more memory. 2x roughly doubles the PDF's native ~72dpi, a common
// practical floor for OCR accuracy without ballooning render time.
const OCR_RENDER_SCALE = 2;
// A page with less real text than this (after collapsing whitespace) is
// treated as having no usable text layer -- i.e. scanned -- and gets OCR'd
// instead. Catches empty/near-empty pages (stray page numbers, watermarks)
// without needing an exact zero.
const SCANNED_PAGE_TEXT_THRESHOLD = 20;
// tesseract.js caches downloaded trained-data files at `${cachePath}/${lang}.traineddata`,
// defaulting to the current working directory when unset -- which for this
// project means dumping multi-MB files straight into the repo. Point it at
// the OS temp dir instead; it's still reused across conversions within the
// same running process.
const OCR_CACHE_DIR = join(tmpdir(), "kr-tesseract-cache");

function escapeHtml(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

function splitParagraphs(text: string): string[] {
  return text
    .split(/\n{2,}/)
    .map((p) => p.replace(/\s+/g, " ").trim())
    .filter(Boolean);
}

function isScannedPageText(text: string): boolean {
  return text.replace(/\s+/g, "").length < SCANNED_PAGE_TEXT_THRESHOLD;
}

/**
 * OCRs every page in {@code pageNums} (1-based) and returns its recognized
 * text, keyed by page number. Pages that fail OCR are simply omitted rather
 * than aborting the rest -- best effort, same as the rest of this module.
 */
async function ocrPages(parser: PDFParse, pageNums: number[]): Promise<Map<number, string>> {
  const result = new Map<number, string>();
  const screenshots = await parser.getScreenshot({ partial: pageNums, scale: OCR_RENDER_SCALE });
  // tesseract.js writes cached trained-data files under this path but never
  // creates the directory itself -- it just silently fails to cache (and
  // re-fetches every time) if it doesn't already exist.
  await mkdir(OCR_CACHE_DIR, { recursive: true });
  const worker = await createWorker(OCR_LANGUAGES, undefined, { cachePath: OCR_CACHE_DIR });
  try {
    for (const page of screenshots.pages) {
      try {
        const { data } = await worker.recognize(Buffer.from(page.data));
        result.set(page.pageNumber, data.text);
      } catch (err) {
        console.error(`PDF -> EPUB OCR failed for page ${page.pageNumber}:`, err);
      }
    }
  } finally {
    await worker.terminate();
  }
  return result;
}

/**
 * Best-effort PDF -> EPUB conversion: extracts the PDF's text layer plus its
 * embedded images (page by page, each page's images appended after that
 * page's paragraphs -- PDFs have no real paragraph/image interleaving to
 * recover, so this is the closest cheap approximation of reading order).
 * Pages with no real text layer (scanned/photographed pages) are rasterized
 * and run through OCR instead, so scanned PDFs still come out as reflowable
 * text rather than empty pages -- the whole point of converting to EPUB in
 * the first place, so we don't fall back to embedding the raw page image for
 * those (that would just duplicate the OCR'd text as a giant picture).
 *
 * OCR is slow (roughly a second or more per page) and only runs on pages
 * that actually need it, but a long scanned PDF can still make this call
 * take a while -- there's no background job queue here, so a very large
 * scanned book risks a slow request rather than a quick failure.
 *
 * epub-gen-memory only downloads images referenced by `file://` or http(s)
 * URLs in the chapter HTML, not inline `data:` URIs, so extracted/rendered
 * images are written to a temp dir and cleaned up once the EPUB buffer is
 * built.
 */
export async function convertPdfToEpub(pdfBuffer: Buffer, title: string): Promise<Buffer> {
  const parser = new PDFParse({ data: pdfBuffer });
  let textResult: Awaited<ReturnType<PDFParse["getText"]>>;
  let imageResult: Awaited<ReturnType<PDFParse["getImage"]>> | null;
  let ocrTextByPage: Map<number, string>;
  try {
    textResult = await parser.getText();

    try {
      imageResult = await parser.getImage();
    } catch (err) {
      // Image extraction is a bonus, not the point: a malformed image
      // object in the PDF shouldn't sink an otherwise-fine text conversion.
      console.error("PDF -> EPUB image extraction failed, continuing without images:", err);
      imageResult = null;
    }

    const scannedPageNums = textResult.pages
      .filter((page) => isScannedPageText(page.text))
      .map((page) => page.num);
    try {
      ocrTextByPage = scannedPageNums.length
        ? await ocrPages(parser, scannedPageNums)
        : new Map();
    } catch (err) {
      console.error("PDF -> EPUB OCR pass failed, continuing without it:", err);
      ocrTextByPage = new Map();
    }
  } finally {
    await parser.destroy();
  }

  const tmpDir = await mkdtemp(join(tmpdir(), "kr-epub-"));
  try {
    const pageHtml: string[] = [];
    let imageCount = 0;

    for (const page of textResult.pages) {
      const ocrText = ocrTextByPage.get(page.num);
      const paragraphs = splitParagraphs(ocrText ?? page.text);
      const parts = paragraphs.map((p) => `<p>${escapeHtml(p)}</p>`);

      // Only attach a page's embedded figures when we used its real text
      // layer -- an OCR'd page's "image" is the whole scanned page itself,
      // already turned into the text above.
      if (ocrText === undefined) {
        const images = imageResult?.pages.find((p) => p.pageNumber === page.num)?.images ?? [];
        for (const image of images) {
          const filePath = join(tmpDir, `img-${imageCount++}.png`);
          await writeFile(filePath, image.data);
          parts.push(`<img src="file://${filePath}" alt="" />`);
        }
      }

      if (parts.length) {
        pageHtml.push(parts.join("\n"));
      }
    }

    const html = pageHtml.length
      ? pageHtml.join("\n")
      : "<p>No se pudo extraer texto de este PDF.</p>";

    return await epub({ title, tocTitle: "Contenido" }, [{ title, content: html }]);
  } finally {
    await rm(tmpDir, { recursive: true, force: true });
  }
}

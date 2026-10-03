import { z } from "zod";

export const formatSchema = z.enum(["pdf", "epub"]);
const rectSchema = z.object({
  x: z.number().min(0).max(1), y: z.number().min(0).max(1),
  w: z.number().positive().max(1), h: z.number().positive().max(1),
}).refine(r => r.x + r.w <= 1.000001 && r.y + r.h <= 1.000001);

const highlightFields = {
  rects: z.array(rectSchema).max(100),
  anchorQuote: z.string().min(1).max(10000).nullable().optional(),
  anchorPrefix: z.string().max(200).nullable().optional(),
  anchorSuffix: z.string().max(200).nullable().optional(),
  color: z.string().regex(/^#[0-9a-fA-F]{6}$/).optional(),
  note: z.string().max(2000).nullish(),
};

export function validHighlightAnchor(value: { rects: unknown; anchorQuote?: string | null }) {
  const hasRects = Array.isArray(value.rects) && value.rects.length > 0;
  return value.anchorQuote ? !hasRects : hasRects;
}

export const createHighlightSchema = z.object({
  ...highlightFields,
  page: z.number().int().min(0),
  clientId: z.string().regex(/^local-[0-9a-fA-F-]{36}$/).optional(),
}).refine(validHighlightAnchor, { message: "Provide PDF rectangles or an EPUB text anchor" });

export const updateHighlightSchema = z.object(highlightFields).partial();
export const progressSchema = z.object({ page: z.number().int().min(0), format: formatSchema.optional() });

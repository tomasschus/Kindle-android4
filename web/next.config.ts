import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // pdf-parse (via pdfjs-dist) dynamically imports a worker file at a path
  // relative to its own module location; bundling it breaks that, so it
  // needs to be loaded via plain `require()` from node_modules instead.
  serverExternalPackages: ["pdf-parse", "pdfjs-dist"],
};

export default nextConfig;

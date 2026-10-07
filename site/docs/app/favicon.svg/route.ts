import { readFile } from "node:fs/promises";
import { repositoryFile } from "@/lib/repository";

/** Rondo's icon, as the design tokens draw it from the brand artwork. */
export const revalidate = false;

export async function GET(): Promise<Response> {
  const icon = await readFile(repositoryFile("brand", "dist", "svg", "favicon.svg"));
  return new Response(icon, { headers: { "Content-Type": "image/svg+xml" } });
}

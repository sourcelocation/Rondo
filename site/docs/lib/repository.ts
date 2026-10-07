import path from "node:path";

/** A file of the repository the documentation is built from (builds run in site/docs). */
export function repositoryFile(...segments: string[]): string {
  return path.join(process.cwd(), "..", "..", ...segments);
}

import { createFromSource } from "fumadocs-core/search/server";
import { source } from "@/lib/source";

/** The search index, exported with the pages; the search dialog loads it. */
export const revalidate = false;
export const { staticGET: GET } = createFromSource(source, { language: "english" });

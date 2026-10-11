import type { APIRoute, GetStaticPaths, InferGetStaticPropsType } from "astro";
import { pressFiles } from "../../press";

/** The press kit's files, at /press/<name>, made at build time (src/press.ts). */
export const getStaticPaths = (() =>
  pressFiles.map((file) => ({ params: { file: file.name }, props: { file } }))) satisfies GetStaticPaths;

export const GET: APIRoute<InferGetStaticPropsType<typeof getStaticPaths>> = async ({ props }) =>
  new Response(new Uint8Array(await props.file.make()), { headers: { "Content-Type": props.file.type } });

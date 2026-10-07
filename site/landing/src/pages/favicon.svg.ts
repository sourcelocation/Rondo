import favicon from "../../../../brand/dist/svg/favicon.svg?raw";

/** Rondo's icon, as the design tokens draw it from the brand artwork. */
export const GET = (): Response => new Response(favicon, { headers: { "Content-Type": "image/svg+xml" } });

import { loader } from "fumadocs-core/source";
import { lucideIconsPlugin } from "fumadocs-core/source/lucide-icons";
import { metaSchema, pageSchema } from "fumadocs-core/source/schema";
import { defineDocs } from "fumadocs-mdx/macro";
import type { DistributiveOmit, OperationOutput, PagesBuilder, WebhookOutput } from "fumadocs-openapi";
import { openapi } from "./openapi";

const guides = defineDocs({
  dir: "content",
  docs: { schema: pageSchema },
  meta: { schema: metaSchema },
});

/**
 * Every page: the guides (content/) and the API reference — an overview (content/api) and a page
 * per operation, by tag (/api/<tag>/<operation>).
 */
export const source = loader(
  {
    guides: guides.toFumadocsSource(),
    api: await openapi.staticSource({
      baseDir: "api",
      groupBy: "tag",
      meta: true,
      slugify: kebabCase,
      name: operationName,
    }),
  },
  { baseUrl: "/", plugins: [openapi.loaderPlugin(), lucideIconsPlugin()] },
);

/** An operation's address: its ID (`pushChanges` → `push-changes`). */
function operationName(this: PagesBuilder, output: DistributiveOmit<OperationOutput | WebhookOutput, "path">): string {
  const id = output.type === "operation" ? this.fromExtractedOperation(output.item)?.operation.operationId : undefined;
  if (id === undefined) throw new Error(`${output.item.method.toUpperCase()} has no operationId`);
  return kebabCase(id);
}

/** `pushChanges` → `push-changes`, `OAuth` → `oauth`. */
function kebabCase(name: string): string {
  return name.replace(/([a-z0-9])([A-Z])/g, "$1-$2").toLowerCase();
}

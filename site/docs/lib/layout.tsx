import type { BaseLayoutProps } from "fumadocs-ui/layouts/shared";

/** The web app (.env.development, .env.production). */
const appUrl = process.env.NEXT_PUBLIC_RONDO_APP_URL ?? "https://rondo.matthewsource.com/app/";

/** What every page's navigation shows. */
export function baseOptions(): BaseLayoutProps {
  return {
    nav: { title: "Rondo Docs" },
    links: [{ text: "Open Rondo", url: appUrl, external: true }],
    githubUrl: "https://github.com/sourcelocation/rondo",
  };
}

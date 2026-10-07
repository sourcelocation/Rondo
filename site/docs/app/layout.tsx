import "./global.css";
import { DocsLayout } from "fumadocs-ui/layouts/docs";
import type { Metadata } from "next";
import { Provider } from "@/components/provider";
import { baseOptions } from "@/lib/layout";
import { source } from "@/lib/source";

export const metadata: Metadata = {
  metadataBase: new URL("https://rondo.matthewsource.com/docs/"),
  title: { template: "%s · Rondo Docs", default: "Rondo Docs" },
  description: "How to use Rondo, connect assistants to it and build on its API.",
  icons: "/favicon.svg",
};

export default function Layout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en" suppressHydrationWarning>
      <body className="flex min-h-screen flex-col">
        <Provider>
          <DocsLayout tree={source.getPageTree()} {...baseOptions()}>
            {children}
          </DocsLayout>
        </Provider>
      </body>
    </html>
  );
}

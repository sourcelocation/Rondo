import { clientsClaim } from "workbox-core";
import { cleanupOutdatedCaches, createHandlerBoundToURL, precacheAndRoute } from "workbox-precaching";
import { NavigationRoute, registerRoute } from "workbox-routing";
import icon from "../../../../brand/dist/email/mark.png?url";

// Rondo's service worker. Offline-first: the app itself comes from the cache, and every navigation
// under /app falls back to it. Reminders and other pushes from the server show as notifications.
declare let self: ServiceWorkerGlobalScope;

self.skipWaiting();
clientsClaim();
// The dev server answers for itself.
if (import.meta.env.PROD) {
  precacheAndRoute(self.__WB_MANIFEST);
  cleanupOutdatedCaches();
  registerRoute(new NavigationRoute(createHandlerBoundToURL("index.html")));
}

/** What the server pushes: notify.Payload. */
type Push = { title?: string; body?: string; path?: string };

self.addEventListener("push", (event) => {
  const push: Push = event.data?.json() ?? {};
  event.waitUntil(
    self.registration.showNotification(push.title ?? "Rondo", {
      body: push.body,
      icon,
      data: { path: push.path ?? "/app/" },
    }),
  );
});

// Tapping one opens its page: in a Rondo tab that's open already, else in a new one.
self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const url = new URL(event.notification.data?.path ?? "/app/", self.location.origin).href;
  event.waitUntil(
    (async () => {
      const tabs = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
      const tab = tabs.find((t) => new URL(t.url).pathname.startsWith("/app"));
      if (!tab) return void (await self.clients.openWindow(url));
      await tab.focus();
      await tab.navigate(url);
    })(),
  );
});

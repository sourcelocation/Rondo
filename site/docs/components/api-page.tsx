"use client";
import { createOpenAPIPage } from "fumadocs-openapi/ui";

/**
 * An operation of the API reference. There is no playground: the API answers only Rondo's own
 * sites, and every call needs a signed-in person.
 */
export const OpenAPIPage = createOpenAPIPage({ playground: { enabled: false } });

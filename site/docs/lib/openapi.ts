import { createOpenAPI } from "fumadocs-openapi/server";
import { repositoryFile } from "./repository";

/** The API's contract (server/api/rondo.yaml): the code both servers are generated from. */
export const openapi = createOpenAPI({
  input: { rondo: repositoryFile("server", "api", "rondo.yaml") },
});

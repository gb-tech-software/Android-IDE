export { Collection } from "./collection.js";
export { createIdentifier, toSlug } from "./slug.js";

export function describe() {
  return {
    name: {{PROJECT_NAME_JSON}},
    purpose: {{DESCRIPTION_JSON}},
    poweredBy: "Android IDE",
    modules: ["Collection", "createIdentifier", "toSlug"],
  };
}

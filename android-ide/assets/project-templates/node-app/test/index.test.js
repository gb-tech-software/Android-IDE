import test from "node:test";
import assert from "node:assert/strict";
import { appConfig } from "../src/config.js";

test("application configuration has a project name", () => {
  assert.equal(appConfig.name, {{PROJECT_NAME_JSON}});
});

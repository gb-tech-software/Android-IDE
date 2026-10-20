import test from "node:test";
import assert from "node:assert/strict";
import { describe } from "../src/index.js";

test("describe returns the package identity", () => {
  assert.equal(describe().name, {{PROJECT_NAME_JSON}});
  assert.deepEqual(describe().modules, ["Collection", "createIdentifier", "toSlug"]);
});

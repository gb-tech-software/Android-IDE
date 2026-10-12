import test from "node:test";
import assert from "node:assert/strict";
import { describe } from "../src/index.js";

test("describe returns the package identity", () => {
  assert.equal(describe(), {{PROJECT_NAME_JSON}} + " created with Android IDE");
});

import test from "node:test";
import assert from "node:assert/strict";
import { createApp } from "../src/app.js";

test("application is an Express-compatible server", () => {
  const app = createApp();
  assert.equal(typeof app.listen, "function");
  assert.equal(typeof app.use, "function");
});

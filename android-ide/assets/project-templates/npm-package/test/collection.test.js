import test from "node:test";
import assert from "node:assert/strict";
import { Collection } from "../src/collection.js";
import { createIdentifier, toSlug } from "../src/slug.js";

test("normalizes labels and creates stable identifier shapes", () => {
  assert.equal(toSlug("Café Tasks"), "cafe-tasks");
  assert.match(createIdentifier("Task"), /^task_[a-z0-9]{10}$/);
});

test("stores and removes domain items", () => {
  const collection = new Collection();
  collection.add({ id: "one", title: "First" });
  assert.equal(collection.size, 1);
  assert.deepEqual(collection.get("one"), { id: "one", title: "First" });
  assert.equal(collection.remove("one"), true);
});

import test from "node:test";
import assert from "node:assert/strict";
import { createTaskStore } from "../src/task-store.js";

test("creates, lists, and completes tasks", () => {
  const store = createTaskStore();
  const task = store.add("Ship the first feature");
  assert.equal(store.list().length, 1);
  assert.equal(store.complete(task.id).completed, true);
});

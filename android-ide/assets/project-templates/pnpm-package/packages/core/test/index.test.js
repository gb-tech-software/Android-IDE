import test from "node:test";
import assert from "node:assert/strict";
import { createTask, formatTask, summarizeTasks } from "../src/index.js";

test("creates a normalized task", () => {
  const task = createTask("  Ship release  ", { id: "task-1" });
  assert.equal(task.title, "Ship release");
  assert.equal(task.completed, false);
});

test("summarizes and formats tasks", () => {
  const tasks = [createTask("one", { id: "one-0000", completed: true }), createTask("two", { id: "two-0000" })];
  assert.deepEqual(summarizeTasks(tasks), { total: 2, completed: 1, pending: 1 });
  assert.match(formatTask(tasks[0]), /Ship|one/);
});

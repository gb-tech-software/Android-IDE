import test from "node:test";
import assert from "node:assert/strict";
import { createTaskRepository } from "../src/task-repository.js";

test("repository creates and completes tasks", () => {
  const repository = createTaskRepository();
  const task = repository.create("Write an API");
  assert.equal(repository.list().length, 1);
  assert.equal(repository.complete(task.id).completed, true);
});

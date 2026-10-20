import test from "node:test";
import assert from "node:assert/strict";
import { createApp } from "../src/app.js";

test("serves health and task resources", async () => {
  const server = createApp().listen(0);
  const { port } = server.address();
  try {
    const health = await fetch(`http://127.0.0.1:${port}/health`);
    assert.equal(health.status, 200);
    const created = await fetch(`http://127.0.0.1:${port}/api/tasks`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ title: "Use the starter" }),
    });
    assert.equal(created.status, 201);
  } finally {
    server.close();
  }
});

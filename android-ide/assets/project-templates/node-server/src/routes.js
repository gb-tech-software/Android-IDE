import { readJson, sendJson } from "./http.js";
import { createTaskRepository } from "./task-repository.js";

export function createRouter(repository = createTaskRepository()) {
  return async function handleRequest(request, response) {
    try {
      if (request.method === "GET" && request.url === "/") {
        return sendJson(response, 200, {
          name: {{PROJECT_NAME_JSON}},
          description: {{DESCRIPTION_JSON}},
          product: "Task API starter",
          poweredBy: "Android IDE",
          next: "Use the API routes to build your product workflow.",
        });
      }
      if (request.method === "GET" && request.url === "/health") return sendJson(response, 200, { status: "ok" });
      if (request.method === "GET" && request.url === "/api/tasks") return sendJson(response, 200, { data: repository.list() });
      if (request.method === "POST" && request.url === "/api/tasks") {
        const task = repository.create((await readJson(request)).title);
        return sendJson(response, 201, { data: task });
      }
      const match = request.url.match(/^\/api\/tasks\/([^/]+)\/complete$/);
      if (request.method === "POST" && match) {
        const task = repository.complete(match[1]);
        return task ? sendJson(response, 200, { data: task }) : sendJson(response, 404, { error: "Task not found" });
      }
      return sendJson(response, 404, { error: "Route not found" });
    } catch (error) {
      return sendJson(response, 400, { error: error.message });
    }
  };
}

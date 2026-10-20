import express from "express";
import helmet from "helmet";
import { createTaskRepository } from "./repositories/task-repository.js";
import { taskRoutes } from "./routes/tasks.js";
import { errorHandler } from "./middleware/errors.js";

export function createApp() {
  const app = express();
  const repository = createTaskRepository();
  app.use(helmet());
  app.use(express.json());
  app.get("/", (_request, response) => response.json({
    name: {{PROJECT_NAME_JSON}},
    description: {{DESCRIPTION_JSON}},
    product: "Express API starter",
    poweredBy: "Android IDE",
    next: "Use the resource routes to build your product workflow.",
  }));
  app.get("/health", (_request, response) => response.json({ status: "ok" }));
  app.use("/api/tasks", taskRoutes(repository));
  app.use(errorHandler);
  return app;
}

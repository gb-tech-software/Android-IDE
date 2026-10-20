import { Router } from "express";

export function taskRoutes(repository) {
  const router = Router();
  router.get("/", (_request, response) => response.json({ data: repository.list() }));
  router.post("/", (request, response, next) => {
    try {
      response.status(201).json({ data: repository.create(request.body?.title) });
    } catch (error) {
      next(error);
    }
  });
  router.post("/:id/complete", (request, response) => {
    const task = repository.complete(request.params.id);
    if (!task) return response.status(404).json({ error: "Task not found" });
    return response.json({ data: task });
  });
  return router;
}

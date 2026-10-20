export function createTaskRepository() {
  const tasks = new Map();
  return {
    list() { return [...tasks.values()]; },
    create(title) {
      const task = { id: crypto.randomUUID(), title: String(title || "").trim(), completed: false };
      if (!task.title) throw new Error("title is required");
      tasks.set(task.id, task);
      return task;
    },
    complete(id) {
      const task = tasks.get(id);
      if (!task) return null;
      task.completed = true;
      return task;
    },
  };
}

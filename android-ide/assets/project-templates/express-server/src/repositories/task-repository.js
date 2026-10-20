export function createTaskRepository() {
  const tasks = new Map();
  return {
    list: () => [...tasks.values()],
    create(title) {
      const cleanTitle = String(title || "").trim();
      if (!cleanTitle) throw new Error("title is required");
      const task = { id: crypto.randomUUID(), title: cleanTitle, completed: false };
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

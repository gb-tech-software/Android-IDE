export function createTaskStore() {
  const tasks = new Map();
  return {
    add(title) {
      const task = { id: crypto.randomUUID(), title: String(title).trim(), completed: false };
      if (!task.title) throw new Error("Task title is required");
      tasks.set(task.id, task);
      return task;
    },
    list() {
      return [...tasks.values()];
    },
    complete(id) {
      const task = tasks.get(id);
      if (!task) return null;
      task.completed = true;
      return task;
    },
  };
}

export function createTaskStore(initialTasks = []) {
  const tasks = [...initialTasks];
  return {
    all: () => [...tasks],
    add(title) {
      const cleanTitle = String(title || "").trim();
      if (!cleanTitle) return null;
      const task = { id: crypto.randomUUID(), title: cleanTitle, completed: false };
      tasks.push(task);
      return task;
    },
    toggle(id) {
      const task = tasks.find((candidate) => candidate.id === id);
      if (task) task.completed = !task.completed;
      return task;
    },
  };
}

import pc from "picocolors";

export function createTask(title, options = {}) {
  const cleanTitle = String(title || "").trim();
  if (!cleanTitle) throw new Error("A task title is required");
  return {
    id: options.id || crypto.randomUUID(),
    title: cleanTitle,
    completed: Boolean(options.completed),
    createdAt: options.createdAt || new Date().toISOString(),
  };
}

export function formatTask(task) {
  const marker = task.completed ? pc.green("✓") : pc.yellow("○");
  return `${marker} ${task.id.slice(0, 8)} ${task.title}`;
}

export function summarizeTasks(tasks) {
  return {
    total: tasks.length,
    completed: tasks.filter((task) => task.completed).length,
    pending: tasks.filter((task) => !task.completed).length,
  };
}

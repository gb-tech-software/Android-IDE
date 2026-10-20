const storageKey = "{{PACKAGE_NAME}}:tasks";

export function createTaskStore() {
  const tasks = JSON.parse(localStorage.getItem(storageKey) || "[]");
  const persist = () => localStorage.setItem(storageKey, JSON.stringify(tasks));
  return {
    all: () => [...tasks],
    add(title) {
      const cleanTitle = String(title || "").trim();
      if (!cleanTitle) return null;
      const task = { id: crypto.randomUUID(), title: cleanTitle, completed: false };
      tasks.push(task); persist(); return task;
    },
    toggle(id) {
      const task = tasks.find((candidate) => candidate.id === id);
      if (task) { task.completed = !task.completed; persist(); }
      return task;
    },
  };
}

export function renderTaskList(root, store) {
  const render = () => {
    root.innerHTML = store.all().map((task) => `
      <label class="task ${task.completed ? "task--complete" : ""}">
        <input type="checkbox" data-task-id="${task.id}" ${task.completed ? "checked" : ""}>
        <span>${task.title}</span>
      </label>
    `).join("") || '<p class="empty">Add your first task to begin.</p>';
    root.querySelectorAll("[data-task-id]").forEach((input) => {
      input.addEventListener("change", () => { store.toggle(input.dataset.taskId); render(); });
    });
  };
  render();
  return render;
}

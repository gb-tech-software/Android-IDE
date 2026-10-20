export function renderTaskView(root, store) {
  root.innerHTML = `
    <section class="dashboard">
      <header><p class="eyebrow">Built with Android IDE</p><h1>{{PROJECT_NAME_HTML}}</h1><p>{{DESCRIPTION_HTML}}</p><p>Start with a focused workflow, then grow this browser project into your product.</p></header>
      <form data-form><input name="title" placeholder="Add a task" required><button>Add</button></form>
      <div data-list class="task-list"></div>
    </section>
  `;
  const list = root.querySelector("[data-list]");
  const render = () => {
    list.innerHTML = store.all().map((task) => `<label class="task"><input type="checkbox" data-id="${task.id}" ${task.completed ? "checked" : ""}><span>${task.title}</span></label>`).join("") || "<p>No tasks yet.</p>";
    list.querySelectorAll("[data-id]").forEach((input) => input.addEventListener("change", () => { store.toggle(input.dataset.id); render(); }));
  };
  root.querySelector("[data-form]").addEventListener("submit", (event) => { event.preventDefault(); const input = event.currentTarget.elements.title; if (store.add(input.value)) { input.value = ""; render(); } });
  render();
}

import { renderTaskList } from "./components/task-list.js";
import { createTaskStore } from "./features/tasks.js";

export function renderApp(root, { projectName, description }) {
  const store = createTaskStore();
  root.innerHTML = `
    <main class="container">
      <header class="hero"><p class="eyebrow">Built with Android IDE</p><h1>${projectName}</h1><p>${description}</p><p>Start with a focused workflow, then grow this interface into your product.</p></header>
      <section class="panel" aria-labelledby="tasks-heading">
        <div class="panel__heading"><h2 id="tasks-heading">Project tasks</h2><span class="badge">Vite</span></div>
        <form data-task-form class="task-form"><input name="title" placeholder="What needs to be done?" required><button>Add task</button></form>
        <div data-task-list class="task-list"></div>
      </section>
    </main>
  `;
  const render = renderTaskList(root.querySelector("[data-task-list]"), store);
  root.querySelector("[data-task-form]").addEventListener("submit", (event) => {
    event.preventDefault();
    const input = event.currentTarget.elements.title;
    if (store.add(input.value)) { input.value = ""; render(); }
  });
}

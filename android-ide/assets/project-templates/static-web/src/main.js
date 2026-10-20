import { appState } from "./state.js";
import { createTaskStore } from "./task-store.js";
import { renderTaskView } from "./view.js";

const main = document.querySelector("main");
appState.ready = true;
main.dataset.ready = String(appState.ready);
renderTaskView(main, createTaskStore());

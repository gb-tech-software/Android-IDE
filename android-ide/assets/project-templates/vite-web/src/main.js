import "./style.css";
import { renderApp } from "./app.js";

renderApp(document.querySelector("#app"), {
  projectName: {{PROJECT_NAME_JSON}},
  description: {{DESCRIPTION_JSON}},
});

import "./style.css";

const app = document.querySelector("#app");
const projectName = {{PROJECT_NAME_JSON}};
const description = {{DESCRIPTION_JSON}};

app.innerHTML = `
  <main class="container">
    <p class="eyebrow">Created with Android IDE</p>
    <h1>${projectName}</h1>
    <p>${description}</p>
    <a href="https://vite.dev/" target="_blank" rel="noreferrer">Learn about Vite</a>
  </main>
`;

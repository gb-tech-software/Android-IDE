import { createApp } from "./app.js";

const app = createApp();
const port = Number(process.env.PORT || 3000);

app.listen(port, () => {
  console.log({{PROJECT_NAME_JSON}} + ` listening on port ${port}`);
});

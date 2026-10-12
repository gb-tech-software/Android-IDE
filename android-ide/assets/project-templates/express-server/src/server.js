import express from "express";

const app = express();
const port = Number(process.env.PORT || 3000);

app.use(express.json());

app.get("/", (_request, response) => {
  response.json({
    name: {{PROJECT_NAME_JSON}},
    status: "ok",
  });
});

app.get("/health", (_request, response) => {
  response.json({ status: "ok" });
});

app.listen(port, () => {
  console.log({{PROJECT_NAME_JSON}} + ` listening on port ${port}`);
});

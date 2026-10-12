import { createServer } from "node:http";

createServer((request, response) => {
  response.writeHead(200, { "Content-Type": "text/plain; charset=utf-8" });
  response.end({{PROJECT_NAME_JSON}} + " is running. Continue building it in Android IDE.");
}).listen(3000, () => console.log("Server running on port 3000"));

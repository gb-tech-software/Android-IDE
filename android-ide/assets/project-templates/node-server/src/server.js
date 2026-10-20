import { createServer } from "node:http";
import { createRouter } from "./routes.js";

const port = Number(process.env.PORT || 3000);
createServer(createRouter()).listen(port, () => console.log(`Server running on port ${port}`));

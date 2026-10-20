import { appConfig } from "./config.js";

export function applicationInfo() {
  return { ...appConfig, capabilities: ["add tasks", "list tasks", "complete tasks"] };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  console.log(`${appConfig.name} is ready in ${appConfig.environment}. Use the CLI commands to manage tasks.`);
}

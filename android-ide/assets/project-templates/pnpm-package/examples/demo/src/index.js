import { createTask, formatTask } from "@{{PACKAGE_NAME}}/core";

const welcomeTask = createTask("Explore the shared core package");
console.log("Android IDE workspace demo");
console.log(formatTask(welcomeTask));

#!/usr/bin/env node
import { Command } from "commander";
import { createTaskStore } from "./task-store.js";

const store = createTaskStore();
const program = new Command()
  .name({{PROJECT_NAME_JSON}})
  .description({{DESCRIPTION_JSON}})
  .addHelpText("after", "\nBuilt with Android IDE — a mobile workspace for building and maintaining real projects.\n");

program.command("add <title>").action((title) => {
  const task = store.add(title);
  console.log(`Android IDE task created: ${task.id}: ${task.title}`);
});
program.command("list").action(() => {
  for (const task of store.list()) console.log(`${task.completed ? "✓" : "○"} ${task.title}`);
});
program.parse();

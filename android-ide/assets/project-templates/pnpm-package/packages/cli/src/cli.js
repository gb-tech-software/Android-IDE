#!/usr/bin/env node
import { Command } from "commander";
import { createTask, formatTask, summarizeTasks } from "@{{PACKAGE_NAME}}/core";

const tasks = [];
const program = new Command()
  .name("{{PACKAGE_NAME}}")
  .description("Manage a small task list from the terminal")
  .version("1.0.0")
  .addHelpText("after", "\nBuilt with Android IDE — develop the workspace from an Android device.\n");

program.command("add <title>").description("add a task").action((title) => {
  const task = createTask(title);
  tasks.push(task);
  console.log(formatTask(task));
});

program.command("list").description("list tasks").action(() => {
  tasks.forEach((task) => console.log(formatTask(task)));
  console.log(`${summarizeTasks(tasks).pending} pending · managed with Android IDE`);
});

program.parse();

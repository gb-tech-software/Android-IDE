# {{PROJECT_NAME}}

**Repository**: https://github.com/godlovetikum/Android-IDE

{{DESCRIPTION}}

A practical Node.js command-line application starter created with Android IDE. It demonstrates a small task workflow with a domain store, environment-aware configuration, a command-line interface, and tests you can extend into a real utility or internal tool.

## What you get

This starter is organized as an application rather than a single demonstration file. The task store owns application data and behavior, `src/config.js` keeps runtime configuration in one place, `src/cli.js` is the user-facing command interface, and the tests exercise the domain layer without coupling it to the terminal.

The project is intentionally ready to rename and extend. Replace the task domain with the workflow your product needs, add persistence when the data model is clear, and keep the CLI as the thin boundary around the application services.

## Project structure

- `src/task-store.js` — in-memory domain store for creating, listing, and completing tasks.
- `src/cli.js` — Commander-powered command-line interface.
- `src/config.js` — runtime name and environment configuration.
- `src/index.js` — application information and process entrypoint.
- `test/` — Node’s built-in test suite for the application behavior.

## Development commands

Install dependencies and run the application:

```sh
npm install
npm start
```

Use watch mode while developing, run the CLI, or execute the test suite:

```sh
npm run dev
node src/cli.js add "Plan the next release"
node src/cli.js list
npm test
```

## Continue building with Android IDE

Use Android IDE’s editor and file tree to work across the domain, CLI, and tests without leaving your Android device. Use the terminal for dependency management and scripts, then use the project workspace to keep the application organized as it grows.

## Created with Android IDE

Android IDE is a mobile development workspace for real source code, project files, terminal workflows, browser-based output, and long-running project operations. This starter gives you a useful application shape on day one so your next change can be product work rather than folder setup.

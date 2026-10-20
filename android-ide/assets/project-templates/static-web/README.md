# {{PROJECT_NAME}}

**Repository**: https://github.com/godlovetikum/Android-IDE

{{DESCRIPTION}}

A dependency-free browser application starter created with Android IDE. It provides a persistent task dashboard built with native HTML, CSS, and JavaScript, making it a useful foundation when you want browser capabilities without a framework or bundler.

## What you get

The static application separates state, rendering, and bootstrapping. `src/task-store.js` persists tasks in browser storage, `src/view.js` owns the dashboard view and interactions, and `src/main.js` starts the application. The project is ready for progressively enhanced features while retaining a small, inspectable runtime.

## Project structure

- `index.html` — browser document shell.
- `src/main.js` — application entrypoint.
- `src/task-store.js` — local-storage-backed task state.
- `src/view.js` — dashboard rendering and form interaction.
- `src/state.js` — application readiness state.
- `styles/main.css` — responsive visual styling.

## Development commands

Run the included local server:

```sh
npm run dev
```

The server uses Python’s standard library and listens on port `5173`. Use Android IDE’s browser workflow to inspect the page and its local persistence while developing.

## Continue building with Android IDE

Use the editor for HTML, CSS, and JavaScript, the terminal for the development server, and the project workspace to keep browser features understandable. Add modules only when they represent a real responsibility in the application.

## Created with Android IDE

Android IDE is a mobile development workspace for building browser applications directly on an Android device. This starter gives you a useful persistent interface and clear module boundaries while keeping every part of the runtime visible and portable.

# {{PROJECT_NAME}}

**Repository**: https://github.com/godlovetikum/Android-IDE

{{DESCRIPTION}}

A production-minded Express API starter created with Android IDE. It uses a layered application shape with security middleware, JSON parsing, repository-backed task routes, centralized error handling, and HTTP integration tests.

## What you get

The application is intentionally split by responsibility. `src/app.js` composes middleware and routes without binding to a port, `src/server.js` owns process startup, the repository owns task state, route modules own HTTP semantics, and error middleware keeps failures consistent. That separation makes the starter suitable for adding authentication, persistence, background work, and additional resources.

## Project structure

- `src/server.js` — production process entrypoint.
- `src/app.js` — testable Express application composition.
- `src/routes/` — resource route modules.
- `src/repositories/` — replaceable data-access boundaries.
- `src/middleware/` — cross-cutting request and error behavior.
- `test/` — application and HTTP integration tests.

## API surface

- `GET /health` — service health response.
- `GET /api/tasks` — list tasks.
- `POST /api/tasks` — create a task.
- `POST /api/tasks/:id/complete` — complete a task.

## Development commands

```sh
npm install
npm start
npm run dev
npm test
```

The service listens on `PORT` when provided and defaults to port `3000`. Use Android IDE’s terminal for the server process and its browser workflow for inspecting responses.

## Continue building with Android IDE

Keep application composition, resource routes, persistence, and middleware separate as the project grows. Android IDE gives you the editor, file tree, terminal, browser, and project operations needed to develop that system from an Android device.

## Created with Android IDE

Android IDE is a mobile workspace for building real applications rather than assembling disconnected snippets. This Express starter begins with conventions that support a product: clear boundaries, security defaults, tests, and an API that can grow.

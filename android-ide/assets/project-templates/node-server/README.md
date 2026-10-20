# {{PROJECT_NAME}}

**Repository**: https://github.com/godlovetikum/Android-IDE

{{DESCRIPTION}}

A dependency-light Node.js HTTP API starter created with Android IDE. It demonstrates how to organize a small service around routes, request/response helpers, and a repository boundary while keeping the runtime based on Node’s built-in `node:http` module.

## What you get

The service exposes a health endpoint and a task resource so the project has a useful API shape from the first launch. Routing is separated from persistence, JSON parsing is isolated in `src/http.js`, and the in-memory repository can later be replaced with a database adapter without rewriting the server boundary.

## Project structure

- `src/server.js` — process entrypoint and port configuration.
- `src/routes.js` — HTTP route composition and error handling.
- `src/http.js` — JSON response and request-body helpers.
- `src/task-repository.js` — replaceable task data boundary.
- `test/` — repository and API behavior tests.

## API surface

- `GET /health` — service health response.
- `GET /api/tasks` — list tasks.
- `POST /api/tasks` — create a task with `{ "title": "..." }`.
- `POST /api/tasks/:id/complete` — complete a task.

## Development commands

```sh
npm install
npm start
npm run dev
npm test
```

The service listens on `PORT` when provided and defaults to port `3000`. Use Android IDE’s browser tools to inspect browser-accessible responses while the terminal runs the service.

## Continue building with Android IDE

Use the editor to add resources and service modules, the terminal to manage scripts and dependencies, and the project workspace to keep API boundaries visible. Add authentication, validation, persistence, and deployment configuration as product requirements become clear.

## Created with Android IDE

Android IDE is a mobile development workspace for building and inspecting real services without leaving the device. This starter gives you a working API shape, not an empty server callback, so you can turn the first route into a product surface.

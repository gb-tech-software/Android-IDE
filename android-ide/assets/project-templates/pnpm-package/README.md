# {{PROJECT_NAME}}

**Repository**: https://github.com/godlovetikum/Android-IDE

{{DESCRIPTION}}

A multi-package pnpm workspace created with Android IDE. This starter is for projects that need shared code, more than one executable or publishable package, and a clear boundary between reusable domain logic, command-line tooling, and examples.

## What you get

This is a real workspace rather than a package with a renamed folder. `@{{PACKAGE_NAME}}/core` contains the reusable task domain and formatting utilities. `@{{PACKAGE_NAME}}/cli` consumes that package and exposes a command-line interface. `examples/demo` shows how another workspace project consumes the shared package.

The workspace gives each package its own manifest, scripts, tests, and dependency graph while keeping coordinated development in one repository. Add more packages under `packages/` when a boundary is real; keep examples under `examples/` when they demonstrate consumption rather than become part of the published product.

## Project structure

- `package.json` — workspace-level scripts and package-manager configuration.
- `pnpm-workspace.yaml` — package and example workspace globs.
- `packages/core/` — reusable task domain package with tests.
- `packages/cli/` — Commander-based CLI consuming the core package.
- `examples/demo/` — runnable consumer example.

## Development commands

Install every workspace dependency from the root:

```sh
pnpm install
```

Run all package tests and checks, or target one workspace:

```sh
pnpm test
pnpm lint
pnpm --filter @{{PACKAGE_NAME}}/core test
pnpm --filter @{{PACKAGE_NAME}}/cli dev -- add "Plan the next release"
```

Run the example consumer:

```sh
pnpm --filter {{PACKAGE_NAME}}-demo start
```

## Continue building with Android IDE

Use Android IDE’s file tree to move between package boundaries, its editor to evolve shared contracts, and its terminal to run filtered pnpm commands. When a new feature needs its own lifecycle, dependency set, or release cadence, give it a package boundary instead of enlarging one catch-all module.

## Created with Android IDE

Android IDE is a project workspace for building real multi-package systems from an Android device. This template gives you shared code, an executable package, a consumer example, dependency relationships, and workspace commands so the repository starts with the conventions a growing product needs.

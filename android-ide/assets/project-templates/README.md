# Android IDE project templates

Each directory is a copy-ready starter project. The Android IDE reads the selected directory from packaged assets and writes every file into the user-selected project folder.

Supported placeholders:

- `{{PROJECT_NAME}}` — the validated project folder name
- `{{PROJECT_NAME_JSON}}` — a JSON-escaped project name, including its quotes
- `{{PROJECT_NAME_HTML}}` — an HTML-escaped project name
- `{{PACKAGE_NAME}}` — a lowercase npm-compatible project name
- `{{DESCRIPTION}}` — the user description for Markdown and text files
- `{{DESCRIPTION_JSON}}` — a JSON-escaped description value, including its quotes
- `{{DESCRIPTION_HTML}}` — an HTML-escaped description

Keep paths relative to the template directory. Add a new enum value in `CreateProjectTemplate` and a matching directory here when introducing another starter.

The current directories are `from-scratch`, `node-app`, `npm-package`, `pnpm-package`, `node-server`, `express-server`, `vite-web`, and `static-web`.

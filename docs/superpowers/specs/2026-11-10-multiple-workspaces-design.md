# Multiple workspaces in one viewer

Date: 2026-11-10
Status: approved in conversation, written spec for review
Parent spec: `2026-10-27-stack-tool-design.md` (D18 launcher, D19 workspace, §8 workspace layout). This spec amends
D19: the viewer is no longer bound to the one workspace it was started with.

## 1. Intent

The owner works on two stacks: the sandbox and a real project ("sam"). Today a viewer process serves exactly one
workspace folder and one recordings folder, fixed at startup (`ViewerPaths`), so looking at the other stack means
restarting the viewer or running a second one on another port.

Success: one viewer knows several workspaces, a switcher in the header moves between them, a workspace is added by
pasting its folder into the viewer, and two browser tabs can show two workspaces side by side without disturbing each
other.

Decided with the owner:

- **One viewer with a switcher.** Rejected: running one viewer per workspace on separate ports (works today with no
  code, but means two JVMs and two commands to remember).
- **The workspace is in the URL** (`/w/<name>/...`, `/api/w/<name>/...`), not a server-wide "current workspace".
  Two tabs can show different workspaces; a bookmark always opens the same one. Rejected: one active workspace on the
  server (simpler, but tabs switch each other's workspace underneath them).
- **Added from the viewer by pasting a folder.** Rejected: editing a config file or passing several `--workspace`
  flags (less to build, but adding a workspace means editing a file and restarting).

## 2. What a workspace entry is

An entry in the workspace list has:

| Field        | Meaning                                                                                          |
|--------------|--------------------------------------------------------------------------------------------------|
| `name`       | A slug (`^[a-z0-9][a-z0-9-]*$`), unique in the list; the URL segment                            |
| `workspace`  | Absolute path of the `stack/` folder (the one holding `workspace.yaml`)                          |
| `recordings` | Absolute path of the recordings folder                                                           |

Two entries never point at the same `workspace` folder. Paths are compared after `toAbsolutePath().normalize()`, and
through `toRealPath()` when the folder exists (so case and symlinks on Windows and macOS do not make duplicates).

## 3. The list file

- Default location `~/.stack-viewer/workspaces.yaml`, outside every repository, so it is never committed.
- Overridable with `--workspaces-file=<path>` (a top-level property, like `--workspace`). Every automated test that
  starts the viewer passes a throwaway file, so test runs never touch the owner's list.
- Content: the entries in the order they were added, plus `lastOpened: <name>`.
- Written atomically (temp file and rename, as `FileWorkspaceStore` does). A missing file is an empty list. A file that
  cannot be read is logged as a warning and treated as empty; it is not overwritten until the list next changes, and
  the first write keeps the broken file as `workspaces.yaml.broken`.
- Two viewer processes sharing the file: the last writer wins. Accepted; not guarded.

## 4. Adding a workspace

The owner pastes a folder path. The viewer classifies it:

1. **A project root:** it contains `stack/workspace.yaml`. Workspace `<root>/stack`, recordings `<root>/recordings`.
2. **A `stack/` folder:** it contains `workspace.yaml`. Workspace is the folder, recordings `<parent>/recordings`.
3. **Empty or missing:** a new project root. The viewer creates `<root>/stack/workspace.yaml` with `project.name` set
   to the entry's name; recordings `<root>/recordings` (not created: the reporter creates it).
4. **Anything else** is refused: "Expected a project folder with `stack/workspace.yaml`, a `stack/` folder, or an
   empty folder".

The name defaults to the slug of `project.name` from `workspace.yaml` (lowercased, runs of other characters become
`-`, trimmed of `-`), falling back to the slug of the root folder's name. The owner can edit the name and the
recordings path before adding. Refused as well: a name that is not a slug, a name already in the list, a workspace
folder already in the list. A recordings folder that does not exist yet is accepted (no runs until the reporter writes).

Removing an entry only takes it off the list; no file is deleted. Renaming changes only `name` (old URLs then show
"Unknown workspace").

## 5. Starting the viewer

- `--workspace=<path> [--recordings=<path>]`: as today. The folder is added to the list if it is not there yet
  (its name chosen as in §4; on a name clash `-2`, `-3`... is appended), and it becomes `lastOpened`. Without
  `--recordings`, recordings is the existing entry's recordings, else `recordings/` next to the workspace folder.
  *(Change: today the default is `./recordings` relative to the current directory. For the documented command, run
  from the stack repo's root with `--workspace=stack`, both resolve to the same folder.)*
- No `--workspace`: the viewer serves the list as it is. The UI opens `lastOpened`, or the "Add a workspace" screen
  when the list is empty.
- `ViewerPaths` becomes `workspace: Path?`, `recordings: Path?`, `workspacesFile: Path` (default
  `~/.stack-viewer/workspaces.yaml`). The defaults `./stack` and `./recordings` go away.

## 6. Backend

### 6.1 Modules

- **`workspace`** (existing): `WorkspaceStore` and `FileWorkspaceStore` keep their code. `FileWorkspaceStore` takes its
  root path in its constructor instead of `ViewerPaths` and is no longer a Spring bean. The module's public API gains
  a factory, `WorkspaceStores.open(root: Path): WorkspaceStore`, so other modules never reach into `internal`.
- **`recordings`** (existing): the same treatment. `FileRecordingRepository(recordings: Path, workspace: Path)`, opened
  through `RecordingRepositories.open(recordings, workspace)`.
- **`registry`** (new): owns the list. Public API:
  - `WorkspaceRegistry.list(): WorkspaceList` (entries and `lastOpened`)
  - `add(request: AddWorkspace): WorkspaceEntry` (folder, optional name, optional recordings; the rules of §4;
    `IllegalArgumentException` with the reason on refusal, which the existing `ApiExceptionHandler` turns into a 400)
  - `rename(name, newName)`, `remove(name)`
  - `open(name): OpenWorkspace` returns the entry with its `WorkspaceStore` and `RecordingRepository`, created on
    first use and kept for the life of the process (dropped on rename and remove). An unknown name throws
    `UnknownWorkspaceException`, a `NoSuchElementException`, which `ApiExceptionHandler` already maps to 404.
  - `markOpened(name)`: sets `lastOpened`; writes the file only when it changes.
  - On startup it applies §5 for `--workspace`.
- **`topology`**: `TopologyService` no longer holds a store and a repository. Each method takes the `OpenWorkspace`
  it works on (`workspaceView(ws)`, `flow(ws, runId)`, ...). Its logic does not change.
- **`web`**: controllers resolve `{ws}` through `WorkspaceRegistry.open` and pass the result on.

`ModularityTest` keeps passing: `registry` depends on the public APIs of `workspace` and `recordings`; `topology` and
`web` depend on `registry`'s `OpenWorkspace`.

### 6.2 API

Every existing endpoint moves under the workspace, otherwise unchanged:

| Before                                   | After                                          |
|------------------------------------------|------------------------------------------------|
| `GET /api/workspace`                     | `GET /api/w/{ws}/workspace` (also `markOpened`) |
| `PUT /api/components/{id}`               | `PUT /api/w/{ws}/components/{id}`              |
| `PUT /api/connections/{id}`              | `PUT /api/w/{ws}/connections/{id}`             |
| `GET/PUT /api/notes/{targetId}`          | `GET/PUT /api/w/{ws}/notes/{targetId}`         |
| `GET/PUT /api/boards/{board}/layout`     | `GET/PUT /api/w/{ws}/boards/{board}/layout`    |
| `GET /api/scenarios/{id}/runs`           | `GET /api/w/{ws}/scenarios/{id}/runs`          |
| `GET /api/runs/{runId}/flow`             | `GET /api/w/{ws}/runs/{runId}/flow`            |
| `GET /api/runs/{runId}/screenshots/{id}` | `GET /api/w/{ws}/runs/{runId}/screenshots/{id}` |

New:

- `GET /api/workspaces`: `{ "workspaces": [{ "name", "workspace", "recordings", "projectName" }], "lastOpened" }`.
  `projectName` is read from each `workspace.yaml` (null when unreadable) for the switcher's labels.
- `POST /api/workspaces`: body `{ "folder", "name"?, "recordings"? }`; 201 with the entry, or 400 with the reason.
- `PATCH /api/workspaces/{ws}`: body `{ "name" }`; 200 with the entry, 400, or 404.
- `DELETE /api/workspaces/{ws}`: 204, or 404.

The SPA fallback serves `index.html` for `/w/...` paths as it does for `/sequence/...` today.

The model's view types for these responses go into `tool/model` like the existing ones, so `generated/model.ts` stays
the UI's source of types.

## 7. UI

### 7.1 Routes

- `/w/:ws/` the board, `/w/:ws/sequence/:runId` the sequence view.
- `/` redirects to `/w/<lastOpened>/`, or shows the "Add a workspace" screen when the list is empty.
- A name not in the list shows "Unknown workspace" with the switcher.
- Old URLs (`/sequence/:runId`) redirect to `/`.

### 7.2 Data

- `useWorkspaceName()` reads `:ws` from the route.
- Every API path in `queries.ts` and `Screenshot.tsx` is built under `/api/w/${ws}`; every query key starts with `ws`,
  so cached data from one workspace never shows in another.
- Links (`ScenarioPicker`, `StepDetails`, `SequenceView`'s back button) include `/w/${ws}`.

### 7.3 Switcher

In the header, the project name becomes a dropdown:

- every workspace (its `projectName`, with `name` beneath when they differ), the current one ticked;
- **Add workspace…**: a dialog with the folder path, plus name and recordings fields, which may be left empty to use
  the defaults of §4. A 400 shows its reason inside the dialog. On success it navigates to the new workspace.
- **Manage…**: a list with rename and remove per workspace. Remove asks for confirmation and says no files are
  deleted. Removing the current workspace navigates to `/`.

### 7.4 Client state when switching

The selections in `uiStore` (component, connection, step, scenario, run, hovered connection) are cleared when `ws`
changes. Note drafts are keyed by workspace and target id, so a draft survives switching away and back. Remembered
panel widths stay global.

## 8. Testing

Test-first, as D21.

- **`registry`**: against temporary folders and a temporary list file: the three folder kinds and the refusal; name
  defaulting and slugging; duplicate name and duplicate folder; rename and remove (files untouched); `--workspace`
  at startup (new, already listed, name clash, default recordings); missing and broken list file; `lastOpened`.
- **API tests** move to `/api/w/{ws}/...`. A new test with two workspaces checks that boards, notes, layouts and runs
  stay apart, and that an unknown `{ws}` is 404.
- **`LauncherScriptTest`** and every other test that starts the viewer pass `--workspaces-file` in a temporary folder.
- **UI unit tests**: switcher, add dialog (success and a 400), manage dialog, redirect from `/`, unknown workspace.
  Existing tests render under `/w/<name>/`.
- **Playwright**: `playwright.config.ts` passes a throwaway `--workspaces-file`; a new e2e test adds a second
  workspace (an empty temp folder), switches to it, sees an empty board, and switches back to the fixture board.

## 9. Docs

- Parent spec: note under D19 that the viewer serves several workspaces (this spec).
- `README.md`, `sandbox/README.md`, `docs/integrate-a-project.md`: the existing commands still work; add one paragraph
  on the switcher and on adding a workspace from the viewer.

## 10. Out of scope

- Watching the list file for edits made by another process while the viewer runs.
- Deleting workspace files from the viewer.
- Workspaces on another machine or over the network.

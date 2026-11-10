# Multiple Workspaces Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One viewer knows several workspaces, serves each under `/w/<name>`, and the UI switches between them and adds
new ones from a pasted folder.

**Architecture:** A new `registry` module keeps the list in `~/.stack-viewer/workspaces.yaml` and opens a
`WorkspaceStore` + `RecordingRepository` pair per workspace through factories the `workspace` and `recordings` modules
expose. `TopologyService` and the controllers take the opened workspace per request (`/api/w/{ws}/...`). The UI reads
`:ws` from the route, scopes every API path and query key by it, and gets a header switcher with add and manage dialogs.

**Tech Stack:** Kotlin, Spring Boot + Spring Modulith, Jackson 3 (`tools.jackson`) YAML, JUnit 5 + Kotest matchers,
React + react-router 8 + TanStack Query + zustand, Vitest + Testing Library, Playwright.

**Spec:** `docs/superpowers/specs/2026-11-10-multiple-workspaces-design.md`

Work on a branch, `feat/multiple-workspaces`, not on `master`.

## Global Constraints

- Workspace names are slugs: `^[a-z0-9][a-z0-9-]*$`. Slugging: lowercase, every run of characters outside `[a-z0-9]`
  becomes `-`, leading and trailing `-` trimmed.
- List file default `~/.stack-viewer/workspaces.yaml`; overridable with the top-level property `--workspaces-file=<path>`.
- No automated test ever reads or writes the real `~/.stack-viewer/workspaces.yaml`.
- List file writes are atomic (temp file in the same folder, then move with `ATOMIC_MOVE`, as `FileWorkspaceStore.writeAtomic`).
- Paths compared after `toAbsolutePath().normalize()`, and `toRealPath()` when the folder exists.
- Removing a workspace never deletes a file.
- Unknown workspace name → 404 (via `NoSuchElementException`); refused input → 400 with the reason (via
  `IllegalArgumentException`); both already mapped by `ApiExceptionHandler`.
- Refusal message for an unusable folder, verbatim: ``Expected a project folder with `stack/workspace.yaml`, a `stack/` folder, or an empty folder``.
- UI copy, verbatim: `Add workspace…`, `Manage…`, `Add a workspace`, `Unknown workspace`, remove confirmation
  `Remove <name> from the list? No files are deleted.`
- Paths in code via `java.nio.file.Path` / `path.join` (D26: Windows and macOS).

## Review Focus

1. **A path pasted with quotes or spaces around it** (Windows "Copy as path" gives `"D:\x\sam"`): the viewer strips
   surrounding whitespace and one pair of double quotes before classifying. Test in Task 3.
2. **A relative path pasted** (`sam/stack`): refused with `Use a full path, such as D:\projects\sam or /Users/me/sam`,
   not resolved against wherever the viewer was started. Test in Task 3.
3. **A path to a file** (someone pastes `...\stack\workspace.yaml`): refused with the folder message, no exception
   escapes as a 500. Test in Task 3.
4. **Renaming the workspace you are looking at:** the UI moves to `/w/<new name>/...`, not to "Unknown workspace".
   Test in Task 6.
5. **A project name that slugs to nothing** (`"!!!"` or non-Latin text): the name falls back to the root folder's
   slug, then to `workspace`; with a clash `-2`, `-3`... Test in Task 3.

---

### Task 1: Model types for the workspace list

**Files:**
- Create: `tool/model/src/main/kotlin/io/stackvibed/model/view/WorkspaceListViews.kt`
- Modify: `tool/model/src/main/kotlin/io/stackvibed/model/schema/SchemaGenerator.kt` (`ModelSchemaRoot`)
- Modify: `tool/model/src/test/resources/schema-snapshot/model.schema.json`, `tool/viewer-ui/src/generated/model.ts`,
  `tool/viewer-ui/src/api/types.ts`

**Interfaces:**
- Produces (Kotlin, package `io.stackvibed.model.view`):
  - `data class WorkspaceEntryView(val name: String, val workspace: String, val recordings: String, val projectName: String)`
  - `data class WorkspaceListView(val workspaces: List<WorkspaceEntryView>, val lastOpened: String?)`
  - `data class AddWorkspaceRequest(val folder: String, val name: String? = null, val recordings: String? = null)`
  - `data class RenameWorkspaceRequest(val name: String)`
- Produces (TS, `api/types.ts`): `WorkspaceList`, `WorkspaceEntry`, `AddWorkspaceRequest`, `RenameWorkspaceRequest`
  aliased from `ModelSchema` like the existing ones.

- [ ] **Step 1: Add the four classes** and the fields `workspaceList: WorkspaceListView`,
  `addWorkspaceRequest: AddWorkspaceRequest`, `renameWorkspaceRequest: RenameWorkspaceRequest` to `ModelSchemaRoot`.
- [ ] **Step 2: Run** `tool/gradlew -p tool :model:test`. Expected: FAIL in `SchemaSnapshotTest` (snapshot differs).
- [ ] **Step 3: Regenerate.** `tool/gradlew -p tool :model:generateSchema`, copy `tool/model/build/schema/model.schema.json`
  over the snapshot, then `npm run gen:types` in `tool/viewer-ui`. Add the four aliases to `types.ts`.
- [ ] **Step 4: Run** `tool/gradlew -p tool :model:test :viewer-ui:npmTest`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(model): workspace list views and requests`.

### Task 2: Stores and repositories take their folders

**Files:**
- Modify: `tool/viewer-backend/src/main/kotlin/io/stackvibed/viewer/workspace/internal/FileWorkspaceStore.kt`
- Modify: `tool/viewer-backend/src/main/kotlin/io/stackvibed/viewer/recordings/internal/FileRecordingRepository.kt`
- Create: `tool/viewer-backend/src/main/kotlin/io/stackvibed/viewer/workspace/WorkspaceStores.kt`
- Create: `tool/viewer-backend/src/main/kotlin/io/stackvibed/viewer/recordings/RecordingRepositories.kt`
- Create (temporary, deleted in Task 4): `tool/viewer-backend/src/main/kotlin/io/stackvibed/viewer/StorageBeans.kt`
- Test: `FileWorkspaceStoreTest.kt`, `FileRecordingRepositoryTest.kt`, `TopologyServiceTest.kt` (constructor calls only)

**Interfaces:**
- Produces:
  - `object WorkspaceStores { fun open(root: Path): WorkspaceStore }`
  - `object RecordingRepositories { fun open(recordings: Path, workspace: Path): RecordingRepository }`
  - `FileWorkspaceStore(root: Path)`, `FileRecordingRepository(recordings: Path, workspace: Path)`; neither is a Spring
    bean any more.

- [ ] **Step 1: Change the constructors**, drop `@Component`, add the two factories. Add `StorageBeans`, an
  `@Configuration` in package `io.stackvibed.viewer` with `@Bean` methods building both from `ViewerPaths`, so the app
  still wires while the controllers use single beans.
- [ ] **Step 2: Update the three tests' constructor calls** (`FileWorkspaceStore(workspaceDir)`,
  `FileRecordingRepository(recordingsDir, workspaceDir)`).
- [ ] **Step 3: Run** `tool/gradlew -p tool :viewer-backend:test`. Expected: PASS, no test changed besides constructors.
- [ ] **Step 4: Commit** `refactor(viewer): stores and repositories are opened on given folders`.

### Task 3: The `registry` module

**Files:**
- Create in `tool/viewer-backend/src/main/kotlin/io/stackvibed/viewer/registry/`:
  `WorkspaceRegistry.kt` (interface), `WorkspaceEntry.kt`, `OpenWorkspace.kt`, `UnknownWorkspaceException.kt`
- Create in `.../registry/internal/`: `FileWorkspaceRegistry.kt`, `WorkspaceListFile.kt`, `FolderClassifier.kt`, `Slugs.kt`
- Test: `tool/viewer-backend/src/test/kotlin/io/stackvibed/viewer/registry/FileWorkspaceRegistryTest.kt`,
  `WorkspaceListFileTest.kt`, `FolderClassifierTest.kt`

**Interfaces:**
- Consumes: `WorkspaceStores.open`, `RecordingRepositories.open` (Task 2); `AddWorkspaceRequest` (Task 1).
- Produces (package `io.stackvibed.viewer.registry`):
  - `data class WorkspaceEntry(val name: String, val workspace: Path, val recordings: Path)`
  - `class OpenWorkspace(val entry: WorkspaceEntry, val store: WorkspaceStore, val recordings: RecordingRepository)`
  - `class UnknownWorkspaceException(name: String) : NoSuchElementException("No workspace named '$name'")`
  - `interface WorkspaceRegistry`:
    - `fun list(): List<WorkspaceEntry>` (in the order added)
    - `fun lastOpened(): String?`
    - `fun add(request: AddWorkspaceRequest): WorkspaceEntry`
    - `fun rename(name: String, newName: String): WorkspaceEntry`
    - `fun remove(name: String)`
    - `fun open(name: String): OpenWorkspace` (cached per name; cache entry dropped on rename and remove)
    - `fun markOpened(name: String)`
    - `fun registerStartup(workspace: Path, recordings: Path?): WorkspaceEntry` (§5 of the spec; also sets `lastOpened`)
  - `FileWorkspaceRegistry(listFile: Path) : WorkspaceRegistry`. Not yet a bean (Task 4 wires it).
- Internal:
  - `fun slug(text: String): String` (empty result possible)
  - `sealed interface FolderKind { ProjectRoot(root); StackFolder(stack); NewRoot(root) }` and
    `fun classify(input: String): FolderKind` throwing `IllegalArgumentException` with the Global Constraints message.
  - `class WorkspaceListFile(path: Path) { fun read(): Pair<List<WorkspaceEntry>, String?>; fun write(entries, lastOpened) }`.
    Format:
    ```yaml
    lastOpened: sam
    workspaces:
      - name: sandbox
        workspace: D:/andrei/architect-viewer/sandbox/stack
        recordings: D:/andrei/architect-viewer/sandbox/recordings
    ```

- [ ] **Step 1: Write `FolderClassifierTest`** (temp dirs):
  - `a folder with stack workspace yaml is a project root`, `a folder with workspace yaml is a stack folder`,
    `an empty folder and a missing folder are new roots`
  - `a folder with other files is refused` → message equals the Global Constraints text
  - `a file is refused with the folder message` (Review Focus 3)
  - `surrounding whitespace and one pair of double quotes are stripped` (Review Focus 1): `"  \"$root\"  "` classifies as `ProjectRoot(root)`
  - `a relative path is refused` → message `Use a full path, such as D:\projects\sam or /Users/me/sam` (Review Focus 2)
- [ ] **Step 2: Write `WorkspaceListFileTest`**: round trip; `a missing file is an empty list`;
  `a broken file reads as empty and is kept as workspaces.yaml.broken on the next write` (the broken bytes are in
  `.broken`, the new list in `workspaces.yaml`).
- [ ] **Step 3: Write `FileWorkspaceRegistryTest`** (temp dirs, list file under the temp dir):
  - `adding a project root uses its stack and recordings folders`; `adding a stack folder takes recordings next to it`
  - `adding an empty folder creates stack workspace yaml with the name as project name` (`recordings` not created)
  - `the name defaults to the slug of project name` (`project.name: "Sam Project"` → `sam-project`)
  - `a project name that slugs to nothing falls back to the folder name then to workspace` (Review Focus 5:
    `"!!!"` in folder `Sam_Stack` → `sam-stack`; `"!!!"` in folder `___` → `workspace`)
  - `a given name and recordings path win over the defaults`
  - `a name that is not a slug is refused`; `a taken name is refused`;
    `the same folder written differently is refused` (`$root/stack/` vs `$root/./stack`)
  - `a recordings folder that does not exist is accepted`
  - `rename changes only the name`; `remove leaves every file in place`; unknown names throw `UnknownWorkspaceException`
  - `open returns the same instance until renamed or removed`
  - `markOpened persists lastOpened and does not rewrite an unchanged file` (file modification time unchanged)
  - `registerStartup adds a new folder and makes it lastOpened`; `registerStartup reuses a listed folder`;
    `registerStartup appends -2 on a name clash`; `registerStartup without recordings takes the listed ones, else next to the workspace`
  - `a new registry instance reads what the previous one wrote`
- [ ] **Step 4: Run** `tool/gradlew -p tool :viewer-backend:test --tests '*registry*'`. Expected: FAIL (types missing).
- [ ] **Step 5: Implement** the files above. The list YAML uses a `YAMLMapper` configured like `FileWorkspaceStore`'s;
  paths are written with forward slashes (`toString().replace('\\', '/')`) so the file reads the same on both systems.
- [ ] **Step 6: Run** the same command. Expected: PASS.
- [ ] **Step 7: Commit** `feat(viewer): registry module keeps the list of workspaces`.

### Task 4: Serve every workspace under `/api/w/{ws}`

**Files:**
- Modify: `tool/viewer-backend/src/main/kotlin/io/stackvibed/viewer/ViewerPaths.kt`, `ViewerApplication.kt`
- Delete: `tool/viewer-backend/src/main/kotlin/io/stackvibed/viewer/StorageBeans.kt`
- Create: `tool/viewer-backend/src/main/kotlin/io/stackvibed/viewer/registry/internal/RegistryConfiguration.kt`
- Modify: `topology/TopologyService.kt`, `web/WorkspaceController.kt`, `web/NotesController.kt`, `web/LayoutController.kt`,
  `web/RecordingsController.kt`
- Create: `tool/viewer-backend/src/main/kotlin/io/stackvibed/viewer/web/WorkspacesController.kt`
- Modify tests: `ViewerPathsTest.kt`, `ModularityTest.kt`, `TopologyServiceTest.kt`, `web/ApiTest.kt`,
  `web/SpaFallbackTest.kt`, `LauncherScriptTest.kt`
- Modify: `tool/viewer-ui/playwright.config.ts`, `tool/viewer-backend/src/dist/stack-viewer`, `stack-viewer.cmd` (usage lines)

**Interfaces:**
- Consumes: `WorkspaceRegistry`, `OpenWorkspace` (Task 3); views (Task 1).
- Produces:
  - `ViewerPaths(workspace: Path? = null, recordings: Path? = null, workspacesFile: Path = Path.of(System.getProperty("user.home"), ".stack-viewer", "workspaces.yaml"))`
  - `TopologyService.workspaceView(ws: OpenWorkspace): WorkspaceView`, `TopologyService.flow(ws: OpenWorkspace, runId: String): Flow`
  - The API of spec §6.2. `GET /api/w/{ws}/workspace` also calls `markOpened(ws)`.

- [ ] **Step 1: Update the tests first.**
  - `ViewerPathsTest`: defaults are `null`, `null`, `<user.home>/.stack-viewer/workspaces.yaml`; `workspaces-file=/w/list.yaml` binds.
  - `ModularityTest`: module set is `workspace`, `recordings`, `registry`, `topology`, `web`.
  - `TopologyServiceTest`: builds an `OpenWorkspace` from the two factories and passes it.
  - `ApiTest`: registers `workspaces-file` in the temp root too. The startup workspace must have a known name, so
    `paths()` writes `stack/workspace.yaml` with `project: {name: api}` before the context starts, and `@BeforeEach`
    writes it again after clearing the folder. Every path moves under `/api/w/api/`. New tests: `the workspace list shows the startup workspace as last opened`;
    `adding a second workspace keeps boards notes layouts and runs apart` (POST an empty temp folder named `other`,
    write a note and a layout in each, check each reads its own, `GET /api/w/other/scenarios/create-order/runs` is `[]`);
    `an unknown workspace is 404`; `adding an unusable folder is 400 with the reason`;
    `renaming and removing through the api` (PATCH → 200 with the new name; DELETE → 204, then GET under it → 404).
  - `LauncherScriptTest`: every run that starts a real viewer adds `--workspaces-file=<temp dir>/workspaces.yaml`.
  - `SpaFallbackTest`: `/w/sam/sequence/run-1` serves `index.html`; `/api/w/sam/nope` stays a 404.
- [ ] **Step 2: Run** `tool/gradlew -p tool :viewer-backend:test`. Expected: FAIL (paths, constructors, module set).
- [ ] **Step 3: Implement.** `RegistryConfiguration` declares the `FileWorkspaceRegistry(paths.workspacesFile)` bean
  and, when `paths.workspace` is set, calls `registerStartup(paths.workspace, paths.recordings)`. Controllers take
  `@PathVariable ws: String` and call `registry.open(ws)`. `WorkspacesController` maps entries to
  `WorkspaceEntryView` with `projectName = registry.open(name).store.load().config.project.name`. Delete `StorageBeans`.
- [ ] **Step 4: Update** `playwright.config.ts`: a throwaway `--workspaces-file` in the `mkdtemp` folder, readiness
  URL `/api/workspaces`. Update the launcher usage comments to mention `[--workspaces-file=<path>]`.
- [ ] **Step 5: Run** `tool/gradlew -p tool :viewer-backend:test`. Expected: PASS. (The e2e tests stay red until Task 5.)
- [ ] **Step 6: Commit** `feat(viewer): every workspace in the list is served under /api/w/{ws}`.

### Task 5: UI routes and data scoped by workspace

**Files:**
- Create: `tool/viewer-ui/src/workspaces/useWorkspaceName.ts`, `tool/viewer-ui/src/workspaces/WorkspaceScope.tsx`,
  `tool/viewer-ui/src/workspaces/Home.tsx`
- Modify: `App.tsx`, `api/queries.ts`, `api/client.ts`, `sequence/Screenshot.tsx`, `panel/StepDetails.tsx`,
  `scenarios/ScenarioPicker.tsx`, `sequence/SequenceView.tsx`, `panel/NotesPanel.tsx`, `store/uiStore.ts`
- Test: every existing `*.test.tsx` that stubs `/api/...` or renders routes; new `workspaces/WorkspaceScope.test.tsx`,
  `workspaces/Home.test.tsx`

**Interfaces:**
- Consumes: the API of Task 4.
- Produces:
  - `useWorkspaceName(): string` (throws outside a `/w/:ws` route)
  - `apiBase(ws: string): string` → `/api/w/${encodeURIComponent(ws)}` (in `queries.ts`)
  - `keys` entries all start with `['w', ws, ...]`; `keys.workspaces = ['workspaces']`
  - `useWorkspaces()` → `WorkspaceList` from `GET /api/workspaces`
  - `postJson<T>(path, body): Promise<T>`, `patchJson<T>(path, body): Promise<T>`, `deleteRequest(path): Promise<void>` in `client.ts`
  - `sequenceLink(ws: string, runId: string, stepId: string): string`
  - `uiStore.resetSelection(): void`; note drafts keyed `` `${ws}/${targetId}` ``
  - Routes: `/` → `Home`; `/w/:ws/*` → `WorkspaceScope` wrapping `''` (board) and `sequence/:runId`; anything else → `/`.

- [ ] **Step 1: Write the new tests.**
  - `Home.test.tsx`: `redirects to the last opened workspace` (list `{lastOpened: 'sam'}` → location `/w/sam/`);
    `shows the add screen when the list is empty` (heading `Add a workspace`; Task 6 fills it with the form).
  - `WorkspaceScope.test.tsx`: `an unknown name shows Unknown workspace`;
    `switching workspace clears the selection but keeps each workspace's note drafts` (select a component and set a
    draft under `/w/a/`, navigate to `/w/b/`: `selectedComponentId` is null; back to `/w/a/`: the draft is there);
    `queries of two workspaces do not share cache` (`/api/w/a/workspace` and `/api/w/b/workspace` return different
    project names; the header shows the right one after switching).
- [ ] **Step 2: Move the existing tests** to `MemoryRouter initialEntries={['/w/test/']}` (or `/w/test/sequence/<run>`)
  under a `/w/:ws/*` route, and their fetch stubs to `/api/w/test/...`.
- [ ] **Step 3: Run** `npm test` in `tool/viewer-ui`. Expected: FAIL.
- [ ] **Step 4: Implement.** `WorkspaceScope` reads `:ws`, waits for `useWorkspaces()`, renders `Unknown workspace`
  when the name is not listed, and calls `resetSelection()` in an effect keyed on `ws`.
- [ ] **Step 5: Run** `npm test` and `npm run build` in `tool/viewer-ui`. Expected: PASS.
- [ ] **Step 6: Run** `tool/gradlew -p tool :viewer-ui:e2eTest`. Expected: PASS (the tests open `/`, which now
  redirects to the startup workspace).
- [ ] **Step 7: Commit** `feat(viewer-ui): routes, api paths and cache scoped by workspace`.

### Task 6: Switcher, add and manage dialogs

**Files:**
- Create: `tool/viewer-ui/src/workspaces/WorkspaceSwitcher.tsx`, `AddWorkspaceDialog.tsx`, `ManageWorkspacesDialog.tsx`
- Modify: `App.tsx` (header), `workspaces/Home.tsx` (the empty-list screen hosts the add form), `api/queries.ts`, `styles.css`
- Test: `workspaces/WorkspaceSwitcher.test.tsx`, `AddWorkspaceDialog.test.tsx`, `ManageWorkspacesDialog.test.tsx`

**Interfaces:**
- Consumes: `useWorkspaces`, `postJson`, `patchJson`, `deleteRequest`, `useWorkspaceName` (Task 5).
- Produces: `useAddWorkspace()`, `useRenameWorkspace()`, `useRemoveWorkspace()` mutations, each invalidating `keys.workspaces`.

- [ ] **Step 1: Write the tests.**
  - Switcher: the header button's name is the current `projectName`; the menu lists every workspace as
    `menuitemradio`, the current one `aria-checked="true"`, `name` shown beneath only when it differs from
    `projectName`; picking one navigates to `/w/<name>/`; it has `Add workspace…` and `Manage…`.
  - Add dialog: fields `Folder`, `Name`, `Recordings folder`; submitting POSTs
    `{folder, name: null, recordings: null}` when the optional fields are empty and navigates to `/w/<returned name>/`;
    a 400 shows its reason in the dialog and keeps it open.
  - Manage dialog: `renaming the current workspace moves to its new address` (Review Focus 4: at
    `/w/sam/sequence/run-1`, rename to `sam2` → location `/w/sam2/sequence/run-1`); remove asks
    `Remove sam from the list? No files are deleted.`, DELETEs on confirm, and navigates to `/` when it was the current one.
  - `Home`: the empty-list screen shows the add form.
- [ ] **Step 2: Run** `npm test`. Expected: FAIL.
- [ ] **Step 3: Implement** the three components and the mutations.
- [ ] **Step 4: Run** `npm test` and `npm run build`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(viewer-ui): switch, add and manage workspaces from the header`.

### Task 7: End-to-end test and docs

**Files:**
- Create: `tool/viewer-ui/e2e/workspaces.spec.ts`
- Modify: `docs/superpowers/specs/2026-10-27-stack-tool-design.md` (D19), `README.md`, `sandbox/README.md`,
  `docs/integrate-a-project.md`

- [ ] **Step 1: Write the e2e test** `adds a workspace, switches to it and back, then removes it`: at `/`, open the
  switcher, `Add workspace…`, Folder = a fresh path under `os.tmpdir()` that does not exist, Name = `scratch`, submit;
  expect the URL `/w/scratch/` and 0 `.react-flow__node`; switch back to the fixture workspace and expect 9 nodes;
  `Manage…` → remove `scratch`, confirm; the switcher no longer lists it (the tests share one backend, so the list
  must end as it began).
- [ ] **Step 2: Run** `tool/gradlew -p tool :viewer-ui:e2eTest`. Expected: PASS.
- [ ] **Step 3: Docs.** D19 gets *(Revised 2026-11-10: one viewer serves several workspaces, see
  `2026-11-10-multiple-workspaces-design.md`.)*. Each README/guide keeps its command and gains one paragraph: the
  header switcher, `Add workspace…` with a pasted folder, the list in `~/.stack-viewer/workspaces.yaml`.
- [ ] **Step 4: Run the whole build** `tool/gradlew -p tool build`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** `test(viewer-ui): e2e for adding and switching workspaces; docs: several workspaces in one viewer`.

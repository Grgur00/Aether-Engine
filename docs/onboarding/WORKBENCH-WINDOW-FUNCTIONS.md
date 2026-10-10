# Workbench Window and Action Functions

[Function index](FUNCTION-INDEX.md) | [Workspace](WORKBENCH-WORKSPACE-FUNCTIONS.md) | [Record dialog](WORKBENCH-RECORD-DIALOG-FUNCTIONS.md) | [Typed values](WORKBENCH-TYPED-VALUE-FUNCTIONS.md)

Source: [AetherWorkbench.java](../../modules/aether-workbench/src/main/java/io/aetherdb/workbench/AetherWorkbench.java).
This reference covers **14 explicit declarations in the complete file**, including
the two anonymous listener methods. Lambdas and the renderer's implicit default
constructor are not additional explicit method declarations.

## Window Architecture

```text
main(arguments) -> event thread -> Aether.openInMemory / Aether.open(path)
open(existing database) -> event thread -> borrowed application database
    -> AetherWorkbench constructor -> DatabaseWorkspace -> build -> refresh
    -> Data Explorer / RPC Frame Inspector / Replication Inspector tabs

toolbar or double-click -> selected view row -> model row -> RecordDialog
    -> DatabaseWorkspace write/encoding -> refresh rows and status
windowClosed -> DatabaseWorkspace.close -> close database only when owned
```

The class assembles Swing controls and dispatches actions. Storage operations,
display-key indexing and typed encoding belong to the workspace. Inspectors are
local codec demonstrations, not connections to RPC or replication services.
There is no background scan, connection pool, distributed node or schema editor
inside this window. Each window creates its own workspace/model/table/status.

## Startup and Ownership Functions

| Function | What It Does | Failure and Ownership Boundaries |
| --- | --- | --- |
| `AetherWorkbench.AetherWorkbench(database, ownsDatabase, persistent, sessionDescription)` | Creates the workspace with explicit ownership, stores the UI mode/description, builds controls and synchronously performs the first `refresh("Ready")`. | Ownership and UI mode are separate booleans. Does not open the database itself or close it in a constructor-failure cleanup block. Swing fields, including JFrame, initialize before the constructor body. |
| `AetherWorkbench.main(arguments)` | Schedules standalone startup using `SwingUtilities.invokeLater`. Attempts system look-and-feel, ignoring the listed reflection/unsupported-look-and-feel failures. No arguments opens an owned in-memory database; otherwise normalizes the first argument into an absolute directory and opens an owned persistent database. Shows the window after initialization. | Extra arguments are ignored; no option parser. Runtime startup failures show an error dialog. Opening and initial scanning occur on the event thread. A failed initialization after acquiring a database is not paired with an explicit close here. Requires a graphical environment. |
| `AetherWorkbench.open(database)` | Rejects null synchronously, then schedules a window borrowing the application's database. Sets `ownsDatabase=false`, `persistent=false`, and description `attached application session`. | Does not infer storage persistence from the supplied database or change look-and-feel. Unlike `main`, the deferred constructor is not wrapped in the startup error handler. The application must keep the database usable while attached. Closing the window must not be interpreted as closing a borrowed database. |

The attached mode's nonpersistent flag controls both the add workflow and the
heading notice. It does not make an attached persistent database in-memory.
The notice about discarding data on window close is therefore not a reliable
ownership or durability description for borrowed sessions. Follow the workspace
ownership flag and actual database implementation, not this label.

The Gradle application entry is configured in
[aether-workbench/build.gradle.kts](../../modules/aether-workbench/build.gradle.kts).
The `run` task uses the repository root as working directory, so relative path
arguments resolve there. A desktop JVM is required; headless workspace tests do
not exercise JFrame construction or native window events.

## Layout and Callback Functions

| Function | What It Does | Boundaries |
| --- | --- | --- |
| `AetherWorkbench.build()` | Configures dispose-on-close, minimum 760 by 500 and initial 980 by 640 window sizes, platform location and close listener. Builds heading/notice, Add/Edit/Delete/Refresh toolbar, single-selection auto-sorted JTable, shared cell renderer, scroll pane/status and three tabs. | Edit/Delete remain enabled even without selection; actions show a selection warning instead. Four preferred column widths are 270/150/430/90; row height is 26. Refresh button calls the same synchronous reload path. No asynchronous operation state or retry queue. |
| `AetherWorkbench.anonymous.windowClosed(event)` | Delegates to `workspace.close()` after disposal. | Workspace decides whether to close the underlying database. The callback adds no confirmation, exception handler or wait-for-background-work policy. Does not call `System.exit`. |
| `AetherWorkbench.anonymous.mouseClicked(event)` | Calls `editRecord()` when the click count is exactly two. | Uses the table's selected row, not coordinates from the event. Does not filter mouse buttons or open an independent row editor. Higher click counts do not satisfy this condition. |

Toolbar listeners are inline lambdas rather than separate declared methods.
They call the same action methods described below; clicking Refresh invokes
`refresh("Refreshed")`. Storage latency blocks the event thread during these
calls, including the full workspace row materialization on refresh.

## Add and Edit Functions

| Function | What It Does | Boundaries |
| --- | --- | --- |
| `AetherWorkbench.addRecord()` | In persistent UI mode requires a selected entry whose display key starts with `collection/`; opens a template/schema-based dialog with a suggested field key and blank structured values, then calls `workspace.addTypedEntry` and refreshes. Otherwise opens an empty plain dialog, checks for an existing display key, calls `workspace.put`, then refreshes. | Persistent mode cannot add the first typed record without an existing template. Prefix checking is a preliminary UI test; workspace validates the template/envelope. Persistent insertion catches `IllegalArgumentException`; the plain path does not wrap its put/refresh. Cancellation performs no write. |
| `AetherWorkbench.suggestedKey(selected)` | Parses `selected.field()` as a UUID; if successful, generates a fresh random UUID string. If parsing throws `IllegalArgumentException`, returns `new-key`. | Does not copy the UUID, inspect descriptors or check collisions. UUID parseability is a display-text heuristic, not evidence of an application key type. Workspace remains responsible for insertion checks. |
| `AetherWorkbench.editRecord()` | Requires selection and `workspace.canEdit`; warns if the value is not editable. Opens a dialog with selected display key/value and the workspace's `keyEditable` decision, then calls `workspace.edit` and refreshes. | Catches `IllegalArgumentException` around edit/refresh only. Dialog acceptance is not schema validation or proof of a successful write outcome. Selection and cached values can become stale if another actor changes the database. |

The persistent add path preserves an existing collection/schema template, not
an arbitrary newly declared schema. `blankStructuredValues` removes prior values
but can leave fields empty that the later encoder rejects. The typed-value and
dialog guides explain those defaults and encoding constraints.

The nonpersistent add path is also used by `open(database)`, including borrowed
persistent databases. It is selected by the window flag, not by a runtime probe.
Key duplicate checking uses the workspace display-key index. Binary-key fidelity,
rename rules and ignored database write-result limitations belong to the workspace
contract; this class does not fix or independently revalidate them.

## Selection, Delete and Status Functions

| Function | What It Does | Boundaries |
| --- | --- | --- |
| `AetherWorkbench.deleteRecord()` | Requires a selected row and modal OK confirmation; then calls `workspace.delete(selected.key())` and refreshes with a deletion message. | Cancel leaves storage unchanged. No delete/refresh exception catch or typed-envelope editability test is added. The status message reflects action flow, not inspection of the database write result. |
| `AetherWorkbench.selectedRow()` | Reads the selected JTable view index; if negative, shows `Select a record first` and returns null. Otherwise converts the view index through the sorter and returns `model.row(modelIndex)`. | Correctly distinguishes sorted view order from model order. Does not rescan storage or verify the record still exists. |
| `AetherWorkbench.refresh(message)` | Replaces model rows using `workspace.rows()`, then updates status with the supplied message, workspace record count and session description. | Full synchronous reload; no timer or external-change subscription. Replacing model data does not explicitly restore selection. If loading fails, there is no local catch or fallback model. |
| `AetherWorkbench.showError(message)` | Displays a modal warning titled `Aether Workbench`, attached to the frame. | No stack trace, log artifact or structured failure result. Startup errors in `main` use a separate error dialog. |

## Grouped Row Rendering

| Function | What It Does | Boundaries |
| --- | --- | --- |
| `AetherWorkbench.GroupedRowRenderer.getTableCellRendererComponent(source, value, selected, focused, viewRow, viewColumn)` | Converts current and preceding view rows to model rows, detects the first visible row or changed group, and suppresses repeated group text in view column zero. Calls the superclass with the adjusted value. For unselected cells chooses a pale alternate background when the group hash parity is even, otherwise the table background. Adds a top separator at group starts and bold group-column text, normal font elsewhere. | Selection background remains the superclass's. Grouping follows adjacent rows in current sorted view order; it does not sort/group the model. Same parity can give adjacent groups the same color. Uses view column zero without converting column order, so movable JTable columns are a renderer limitation. |

The renderer reuses one component, resetting background where unselected,
border and font on each call. Color selection is deterministic by group string
hash, not by alternating group ordinal. Sorting another column can split a group
into several visible runs; each run receives a heading and separator. Rendering
does not mutate record data or materialize a new storage snapshot.

## Tests and Verification Scope

[DatabaseWorkspaceTest](../../modules/aether-workbench/src/test/java/io/aetherdb/workbench/DatabaseWorkspaceTest.java)
covers in-memory workspace/helper behavior, not JFrame startup, action clicks,
native close events, the grouped renderer or attached-mode labels. There is no
dedicated main-window test class in the current module. Compiler-tree inventory
checks all 14 explicit declarations, including the anonymous callbacks; it does
not validate thread responsiveness or desktop interaction.

Together with the workspace, typed-value and dialog references, function-name
inventory now spans **86 declarations across all seven implementation files** in
this module. That coverage does not establish that every Swing path has a test,
that attached labels are accurate, or that all database failures are handled.
Package documentation and the module build descriptor are contextual artifacts,
not additional Java function bodies.

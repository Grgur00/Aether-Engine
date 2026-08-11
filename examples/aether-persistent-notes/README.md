# Aether Persistent Notes

A small Swing application that demonstrates Aether’s persistent typed embedded API.

Its repository uses the same named collection API recommended for applications:

```java
var notes = database.defineCollection(
        "persistent-notes",
        UUID.class,
        Note.class);
```

Aether derives the durable collection identity from the name. The generated
`Note` codec gets its durable schema identity from the committed
`aether-schemas` lock, so application code does not contain UUID literals or a
hand-built `CollectionDefinition`.

Run it from the repository root:

```bash
./gradlew :examples:aether-persistent-notes:run
```

Then:

1. Enter text in the field at the bottom.
2. Select **Add and persist**.
3. Close the window.
4. Run the same command again.
5. Your notes will still appear.

By default, data is stored in:

```text
./examples/aether-persistent-notes/data/aether-notes
```

To choose another directory:

```bash
./gradlew :examples:aether-persistent-notes:run --args="/absolute/path/to/my-notes"
```

Only one running application can open the same directory for writing.

After closing the Notes window, inspect the same data in Aether Workbench:

```bash
./gradlew :modules:aether-workbench:run \
  --args="./examples/aether-persistent-notes/data/aether-notes"
```

The Notes schema is registered with Workbench, so selecting a note and choosing **Edit selected** safely re-encodes its typed value. Close Workbench before reopening the Notes application so the database lock can be transferred cleanly.

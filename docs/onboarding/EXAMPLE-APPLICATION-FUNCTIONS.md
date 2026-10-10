# Example Application Functions

[Typed adapter](TYPED-ADAPTER-FUNCTIONS.md) | [Generated codecs](GENERATED-CONTAINER-FUNCTIONS.md) | [Module builds](MODULE-BUILD-ARCHITECTURE.md)

Sources: [notes](../../examples/aether-persistent-notes/src/main/java/io/aetherdb/examples/notes/)
and [social](../../examples/aether-sample-app/src/main/java/io/aetherdb/examples/social/).
This reference covers all 44 explicit methods/constructors across eight Java
classes/records, plus social `package-info.java` (documentation only).
Both use typed embedded collections, not the remote Java client or training-cache
daemon. The application owns its database; repositories borrow it.

## Architecture and Lifetime

```text
Gradle compilation
  -> annotation processor
  -> generated record codecs
application opens database
  -> repository defines collections
  -> typed collection reads/writes
  -> embedded byte engine
application closes database
  (repository borrows ownership)
```

Notes uses a persistent directory, a Swing list model and one UUID-keyed collection.
Social uses three String-keyed collections and joins their results in application
memory. It has no SQL query planner, secondary author index, automatic cascade,
foreign-key constraint in the engine, or uniqueness transaction around prechecks.
Batch submission groups writes, but preceding reads are not part of a serializable
read-check-write transaction. These examples are useful API walkthroughs, not a
concurrent social service implementation.

## Notes Record and Repository

| Function | Behavior and Boundary |
| --- | --- |
| `Note.Note(id, text)` | Requires nonnull UUID and nonblank text; strips leading/trailing whitespace before storing. Version-1 record with text max-length annotation 4096. Constructor does not directly enforce annotated length. |
| `Note.toString()` | Returns text for Swing list display; omits UUID. |
| `NotesRepository.NotesRepository(database)` | Requires database and defines `persistent-notes` with UUID keys and Note values. Borrows database lifetime. |
| `NotesRepository.add(text)` | Creates random UUID and validated Note, puts it, requires `TypedWriteResult.Applied`; otherwise throws with result. Returns note only on applied write, not on every normal method return from put. |
| `NotesRepository.findAll()` | Materializes scanAll values into a list. Ordering follows collection scan, not note creation time. |
| `NotesRepository.addWelcomeNotesIfEmpty()` | Scans for emptiness and sequentially adds two welcome notes. No single batch or concurrency fence: one insertion can succeed before the second fails; concurrent callers can both seed. |

### Swing Application Functions

| Function | Behavior and Boundary |
| --- | --- |
| `PersistentNotesApplication.PersistentNotesApplication(directory)` | Opens persistent database, constructs repository, seeds welcome values, loads list model and builds window. Constructor work can fail after opening; no constructor-level cleanup finally block. |
| `PersistentNotesApplication.main(arguments)` | Uses first argument as directory, otherwise `examples/aether-persistent-notes/data/aether-notes`; schedules opening/showing on Swing event thread and displays RuntimeException message. Extra arguments are ignored. |
| `PersistentNotesApplication.buildWindow(directory)` | Configures location label, single-selection list, editor/button and window-close listener; packs frame and requests focus. Button and Enter both call addNote. No edit/delete workflow. |
| `PersistentNotesApplication.anonymous.windowClosing(event)` | WindowAdapter callback calls application close; does not use JFrame's automatic close path. |
| `PersistentNotesApplication.addNote()` | Calls repository add with input text, appends successful Note to model, clears/refocuses input. Runtime failure shows dialog; no model append before successful add. |
| `PersistentNotesApplication.close()` | Closes database, displays RuntimeException if closing fails, disposes frame in finally even on failure. Does not keep window open for retry. |

Storage operations happen on the Swing event thread, so slow open/scan/write/close
can stall the UI. No background worker or asynchronous state machine is hidden in
these callbacks. Constructor failure after a database opens can leave ownership
unreleased; documentation does not promise exception-safe initialization that the
source does not implement. Gradle run uses root working directory; direct Java
launches need their own working-directory choice.

## Social Domain Records

All three social records use `@AetherRecord(version=1)`. Max-length annotations
are codec/schema constraints, not length checks in these compact constructors.
Read the generated-codec reference for encoding limits. Constructors validate
local values, not existence of referenced records.

| Function | Behavior and Boundary |
| --- | --- |
| `UserProfile.UserProfile(...)` | Requires nonblank id, username, displayName and email; nonnull bio, location and createdAt; ID cannot contain slash; followerCount must be nonnegative. Does not validate email syntax or enforce unique usernames. |
| `UserProfile.requireText(value, name)` | Rejects null/blank with IllegalArgumentException; preserves original whitespace and case. |
| `SocialPost.SocialPost(...)` | Validates post/author identifiers, nonblank content, nonnull timestamps and updatedAt not before createdAt. Does not trim content or look up author. |
| `SocialPost.requireIdentifier(value, name)` | Rejects null/blank or slash-containing identifier. |
| `FollowRelationship.FollowRelationship(followerId, followedId)` | Validates both identifiers and rejects self-follow. Does not look up profiles. |
| `FollowRelationship.includes(profileId)` | True if either endpoint equals argument; null argument matches neither. |
| `FollowRelationship.requireIdentifier(value, name)` | Rejects null/blank or slash-containing identifier to preserve composite-key delimiter meaning. |

UserProfile annotations: id/username 64, displayName/location 256, email 320 and
bio 4096. SocialPost id/authorId limits are 64 and content 8192; relationship
identifiers have limit 64. These are not claims that every constructed object
has already passed encoding-time validation.

## Social Repository Functions

| Function | Behavior and Boundary |
| --- | --- |
| `SocialNetworkRepository.SocialNetworkRepository(database)` | Requires database; defines `social-profiles`, `social-posts`, `social-follows` with String keys and their respective records. Collection definition does not create engine-level foreign-key enforcement. |
| `SocialNetworkRepository.createProfile(profile)` | Requires profile nonnull and stored ID absent, then puts and returns write result. Check can race; caller must inspect Applied/non-applied outcome. |
| `SocialNetworkRepository.createProfiles(values)` | Builds one typed batch, checks each against stored profiles and stages puts; submits once. Duplicate IDs within the same batch are not independently detected by these stored-value checks. |
| `SocialNetworkRepository.findProfile(id)` | Returns typed get's value Optional; no independent distinction of richer read outcomes here. |
| `SocialNetworkRepository.findProfiles()` | Scans/materializes all profile values; no explicit sort or pagination. |
| `SocialNetworkRepository.updateProfile(profile)` | Requires stored ID present then overwrites complete profile. No compare-and-swap or merge; stale caller fields can overwrite changes. |
| `SocialNetworkRepository.deleteProfile(id)` | Requires profile, scans authored posts and follow edges, refuses deletion while either exists; otherwise deletes. No cascade and no transaction including relation checks. |
| `SocialNetworkRepository.createPost(post)` | Requires existing author and absent post ID, then puts. These checks occur before write submission. |
| `SocialNetworkRepository.findPost(id)` | Returns typed get value Optional. |
| `SocialNetworkRepository.postsByAuthor(authorId)` | Scans every post, filters author, sorts createdAt ascending and materializes. No author index. |
| `SocialNetworkRepository.updatePost(id, content, updatedAt)` | Requires current post, preserves ID/author/createdAt, builds new validated post and overwrites. Validation may reject timestamp/content before submission. |
| `SocialNetworkRepository.deletePost(id)` | Requires post present, deletes and returns result; does not update profiles. |
| `SocialNetworkRepository.follow(followerId, followedId)` | Validates edge and both profiles, requires absent composite edge, then batches edge insertion with followed profile's count plus one. Reads/count calculation precede batch, so concurrent increments can be lost. |
| `SocialNetworkRepository.unfollow(followerId, followedId)` | Requires edge and followed profile, batches edge delete and decremented count clamped at zero. Does not construct a fresh FollowRelationship or revalidate follower profile. |
| `SocialNetworkRepository.followersOf(profileId)` | Scans relationships, selects incoming edges, looks up every follower, throws if an edge points to a missing profile. Not a snapshot join across all reads. |
| `SocialNetworkRepository.feedFor(profileId)` | Collects followed IDs from relationships, scans all posts, filters those authors and sorts newest-first. Does not include own posts unless followed-ID set permits them, nor require queried profile to exist. |
| `SocialNetworkRepository.relationships()` | Scans/materializes all follow values; private helper reused by joins/deletion check. |
| `SocialNetworkRepository.withFollowerCount(profile, count)` | Copies all profile fields into a new UserProfile with changed count; record constructor validates nonnegative count. No write itself. |
| `SocialNetworkRepository.relationshipKey(followerId, followedId)` | Concatenates endpoints with slash. No validation here; follow constructs validated record first, unfollow relies on supplied IDs. |
| `SocialNetworkRepository.requireAbsent(value, type, id)` | Throws IllegalStateException when Optional is present; only a local guard, not reserved-key insertion. |
| `SocialNetworkRepository.requirePresent(value, type, id)` | Returns Optional value or throws descriptive IllegalStateException. |

Only notes' add converts non-Applied writes into exceptions. Social write methods
return the result to callers, and the demo main often ignores it. Read-before-write
guards prove intended sequential behavior, not concurrent uniqueness or atomic
referential integrity. Full scans/materialized lists can grow with dataset size;
these are not optimized feeds or bounded-memory joins.

## Social Console Application

| Function | Behavior and Boundary |
| --- | --- |
| `SocialNetworkApplication.SocialNetworkApplication()` | Private empty constructor prevents ordinary instance construction; main is static. |
| `SocialNetworkApplication.main(arguments)` | Opens in-memory database with no arguments, persistent database from first argument otherwise; try-with-resources closes it. Seeds, updates profile/post, creates/deletes draft and prints profiles, author posts, joined feed and deletion check. Assumes seeded IDs exist. |
| `SocialNetworkApplication.seedIfEmpty(social)` | Returns if any profile exists; otherwise creates two profiles, two posts and one follow through separate calls. Partial existing data is not repaired; calls are not one transaction and returned write results are not checked. |
| `SocialNetworkApplication.profile(...)` | Builds a UserProfile with default bio, zero followers, supplied verified flag and parsed Instant. Parsing errors propagate. |

The fixed demo IDs mean that a persistent store containing unrelated profiles
can skip seeding and subsequently fail required demo lookups. Existing partial
seed state can also fail. The sample is a scripted walkthrough, not an idempotent
migration or recovery routine.

## Implicit and Generated Behavior

Record accessors expose immutable component values: Note id and text; UserProfile
id, username, displayName, email, bio, location, followerCount, verified and createdAt;
SocialPost id, authorId, content, createdAt and updatedAt; FollowRelationship
followerId and followedId. Records also supply component-based equals/hashCode and
toString, except Note overrides toString for display. These compiler-supplied
methods are not separate explicit source declarations in the 44-method inventory.
Generated codec classes/resources come from annotation processing; they are not
handwritten example source files. Follow generated-container and processor
references for lookup, version and schema compatibility behavior.

## Evidence and Test Boundaries

[NotesRepositoryTest](../../examples/aether-persistent-notes/src/test/java/io/aetherdb/examples/notes/NotesRepositoryTest.java)
stores one note, closes and reopens the same directory, and checks its text.
[SocialNetworkRepositoryTest](../../examples/aether-sample-app/src/test/java/io/aetherdb/examples/social/SocialNetworkRepositoryTest.java)
checks sequential CRUD/joins/counts, related-record deletion refusal, duplicate
profile/missing-author/missing-followed-profile rejection and generated profile/post
codec round trips plus unknown-version rejection. These do not test concurrent
guard races, partial seed recovery, Swing interactions or UI responsiveness.

Documentation compiler-tree checks match explicit qualified names against all
example main sources, including the anonymous window callback. Static website
checks validate links/layout, not execution of these applications. No window or
persistent store must be opened to inspect this reference.

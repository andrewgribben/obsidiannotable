# Agent memory

## Always

- **After any app code change:** run `./gradlew installDebug` (not just `assembleDebug`). The user expects the app to be installed on the device every time.

## Learned User Preferences

- App display name is Singularity.
- Vault attachment folder is always a path inside the vault, relative to the inbox folder (e.g. Attachments → inbox/Attachments).
- When attachment path is blank or "/", use vault root for legacy exports; do not use device root or a default location outside the vault.
- Treat attachment path "/" as blank when saving settings (normalize to empty string).
- New home captures are unified Obsidian Excalidraw `.md` files in the vault inbox (`excalidraw-plugin` frontmatter + markdown text + compressed-json drawing); no PDF export and no separate `.flip.excalidraw.md` sidecar.
- Default page background for new notes is set via "Set as default for new notes" in the choose-background dialog (native templates only); do not auto-set default when the user changes a note's background.
- Flip side stores ink in the same vault `.md` file (unified Excalidraw format, vault-wide); strokes auto-save on editor close with no prompt. HWR "To text" (preview + replace/append) is optional in a compact top bar; handwritten text entry into a note is the separate pen-tool button in the reader (bottom bar with Save to note/Discard). New home-screen notes default to this format with timestamp naming, not legacy page/notebook creation.
- Remove upstream Notable settings cruft (sponsor link, check-for-update/version checker against Ethran/notable); this fork is Singularity.
- Scribble-to-delete annotation gesture is removed (misclassified circles deleted words); scribbles map to strikethrough. Annotation gestures: circle → highlight, line through letters → strikethrough, line below → bold. Last annotation save is undoable via ↺ in the reader header (hash-guarded).
- Home screen aggregates inbox captures from all configured vaults; new-note creation prompts for vault; sort/filter dialog includes vault filter (default all). Home card menu offers Pin, Delete ink, and Open text view (replaced long-press pin); pinned captures stay first in pin order regardless of sort mode.
- Commit completed work after each user request unless asked not to; user wants git checkpoints between requests.

## Learned Workspace Facts

- Project is a fork of Notable (Onyx Boox) with Obsidian vault sync; `applicationId` is `com.grib.singularity` (display name Singularity). Build with Java 17 (`JAVA_HOME`), `./gradlew assembleDebug` or `installDebug`.
- Vault root = parent of inbox path; attachment path is always relative to vault root; use `resolveExternalStoragePath` for inbox/attachment and `resolveVaultAttachmentDir(inboxPath, attachmentPath)` for the export directory.
- For "Documents/..." paths use `getExternalStoragePublicDirectory(DIRECTORY_DOCUMENTS)` on Android 10+ so writes succeed.
- Boox device: enable USB Debug Mode and allow USB debugging when prompted for `installDebug`.
- Text recognition (MyScript/InboxSyncEngine): line proximity grouping must run before bullet/list prefix detection; only the first line of a multi-line bullet has a dash — continuation lines do not.
- Flip-side view must reload strokes from the vault note's unified Excalidraw drawing block on open (including strokes added or edited in Obsidian; parser must handle compressed-json).
- Legacy `.flip.excalidraw.md` sidecars are merged via `scripts/unify_flipsides.py` on Mac (`pip3 install -r scripts/requirements.txt`); no in-app legacy conversion/migration.
- KvProxy JSON decode uses `ignoreUnknownKeys = true` so stored settings survive AppSettings field removals.
- See "Always" above: install after app changes.

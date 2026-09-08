# Agent memory

## Always

- **Before any app code change:** commit all existing uncommitted work first so the working tree is clean. Do not start new edits on top of dirty changes unless the user explicitly says otherwise. This is mandatory — never skip the pre-change commit step.
- **After any app code change:** run `./gradlew installDebug` (not just `assembleDebug`). The user expects the app to be installed on the device every time.

## Learned User Preferences

- App display name is Singularity.
- Vault attachment folder is always a path inside the vault, relative to the inbox folder (e.g. Attachments → inbox/Attachments). When attachment path is blank or "/", use vault root for legacy exports (normalize "/" to empty string when saving settings); do not use device root or a default location outside the vault.
- Singularity notes use separate Markdown text files and modern Obsidian Excalidraw drawing attachments (`.excalidraw.md`) in the configured attachment folder. Note metadata links the active drawing; opening defaults to drawing mode when one is associated and to text view otherwise.
- Default page background for new notes is set via "Set as default for new notes" in the choose-background dialog (native templates only); do not auto-set default when the user changes a note's background.
- Flip-side ink lives in a linked modern `.excalidraw.md` attachment; strokes auto-save on editor close with no prompt. HWR "To text" (preview + replace/append) is optional via SingularityToggleIcon in the flip-side sidebar; append preserves the completed drawing in metadata history and creates a new blank active canvas that must reopen immediately from the dashboard. Handwrite-into-note (PenTool) is only in reader annotate mode. Line/shape tool snaps strokes to line, axis-aligned rectangle, or ellipse (DrawingShapeSnap v1); marker pen is a semi-transparent highlighter.
- Remove upstream Notable cruft (sponsor link, version checker, Bug Report in drawing menu); prune dead General settings (toolbar position, NeoTools, monochrome, PDF pagination, continuous stroke slider). This fork is Singularity.
- Scribble-to-delete annotation gesture is removed (misclassified circles deleted words); scribbles map to strikethrough. Annotation gestures: circle → highlight, line through letters → strikethrough, line below → bold. Last annotation save is undoable via ↺ in the reader header (hash-guarded).
- Home bookshelf: inbox captures auto-shown from all configured vaults plus curated notes/folders (long-press Add to bookshelf in file browser or quick switcher); per-vault index; new-note prompts for vault; sort/filter includes vault filter. Card menu (VaultEntryPopupMenu): Pin, Rename, Set cover image, Remove cover, Open text view, Remove from bookshelf (non-destructive), Delete (with confirmation); pinned first; folder drill-down with Close folder/back returning to home root. Vault browser long-press: Add to bookshelf, Rename, Open text, Open drawing, Delete (with confirmation). Covers app-local only. Boox home widget: multi-size/resizable; up to 5 pinned-then-recent covers matching in-app home cards (title + proportional thumbnails; grid rows follow widget height); New note, Daily note (`YYYY-MM-DD.md` in inbox by default; vault-relative `dailyNoteFolder` in AppSettings).
- Native in-app Obsidian Sync (Kotlin obsync port, not obsidian-headless); multiple vaults under one account; home sync icon spins while syncing (tap = manual sync), not an Obsidian launcher. Open notes immediately with background pull and per-screen sync indicator; notify if a newer server version arrives—do not block open on full vault pull.
- Vault reader text editing Phase 1: Source-mode markdown body only (Read | Annotate | Edit); Sliders icon for font size; SingularityToggleIcon for open-drawing; PenTool handwrite only in annotate mode; Excalidraw drawing tail preserved on save via VaultNoteEditModel; Live Preview deferred.
- Three-finger gesture opens QuickSwitcher (vault note search), not legacy QuickNav. Drawing sidebar: sync spinner above back (display-only during pull).
- Commit completed work after each user request unless asked not to; always checkpoint existing work before starting app code changes, then run `./gradlew installDebug` after app changes.

## Learned Workspace Facts

- Project is a fork of Notable (Onyx Boox) with Obsidian vault sync; `applicationId` is `com.grib.singularity` (display name Singularity). Build with Java 17 (`JAVA_HOME`), `./gradlew assembleDebug` or `installDebug`.
- App vault root (`vaultRootDir`) = parent of inbox path; attachment path is relative to vault root; use `resolveExternalStoragePath` and `resolveVaultAttachmentDir`. Obsidian Sync uses `obsidianSyncVaultRoot` = inbox folder (desktop vault root); `.obsync-state.json` lives there.
- For "Documents/..." paths use `getExternalStoragePublicDirectory(DIRECTORY_DOCUMENTS)` on Android 10+ so writes succeed.
- Boox device: enable USB Debug Mode and allow USB debugging when prompted for `installDebug`.
- Text recognition (MyScript/InboxSyncEngine): line proximity grouping must run before bullet/list prefix detection; only the first line of a multi-line bullet has a dash — continuation lines do not.
- Drawing associations use `singularity-drawing` for the active attachment and `singularity-drawing-history` for preserved prior attachments. `ExcalidrawSerializer` writes modern `excalidraw-plugin: parsed` `.excalidraw.md` files and handles compressed-json.
- Existing unified captures are split by the in-app `SeparateDrawingMigrator`; legacy `.flip.excalidraw.md` sidecars can still be merged with `scripts/unify_flipsides.py`.
- KvProxy JSON decode uses `ignoreUnknownKeys = true` so stored settings survive AppSettings field removals.
- Native page backgrounds (`blank`, `lined`, `dotted`, etc.) are drawn in `editor/drawing/backgrounds.kt` — no bundled background image folder in the repo; user-imported images live on device under `{dbDir}/backgrounds/{images|covers|pdfs}/`. New flip-side pages honor `GlobalAppSettings.defaultNativeTemplate`.
- Home capture cover images copy to `backgrounds/covers/`; paths keyed in `AppSettings.homeCaptureCoverImages`.
- Native Obsidian Sync in `io/obsidiansync/` (Kotlin obsync port): `ObsidianSyncManager` push-after-write, background pull on resume/periodic, spinning home indicator; `ObsidianLauncher` fallback when sync not configured. Bookshelf index per vault at `{attachmentDir}/.singularity/bookshelf.json`. Drawing shape snap in `editor/utils/DrawingShapeSnap.kt` (v1: line, axis-aligned rectangle, ellipse). Marker opacity via `markerColorArgb` in `pen.kt`. Shared long-press menus in `VaultEntryPopupMenu.kt`.

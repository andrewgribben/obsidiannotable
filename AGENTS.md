# Agent memory

## Always

- **After any app code change:** run `./gradlew installDebug` (not just `assembleDebug`). The user expects the app to be installed on the device every time.

## Learned User Preferences

- App display name is Singularity.
- Vault attachment folder is always a path inside the vault, relative to the inbox folder (e.g. Attachments → inbox/Attachments).
- When attachment path is blank or "/", use vault root for exports and Save & Exit PDF; do not use device root or a default location outside the vault.
- Treat attachment path "/" as blank when saving settings (normalize to empty string).
- All exports and Save & Exit PDF go to the vault attachment directory; clipboard links are relative to vault root.
- Add the PDF link in the note only when export actually succeeds (check return value); show snackbar on export failure.
- PDF wikilinks in generated MD notes use filename only (no path prefix) since Obsidian resolves unique filenames; format is `PDF: [[filename.pdf]]`, not an embed or full-path link.
- Default page background for new notes is set via "Set as default for new notes" in the choose-background dialog (native templates only); do not auto-set default when the user changes a note's background.

## Learned Workspace Facts

- Project is a fork of Notable (Onyx Boox) with Obsidian vault sync; build with Java 17 (`JAVA_HOME`), `./gradlew assembleDebug` or `installDebug`.
- Vault root = parent of inbox path; attachment path is always relative to vault root; use `resolveExternalStoragePath` for inbox/attachment and `resolveVaultAttachmentDir(inboxPath, attachmentPath)` for the export directory.
- For "Documents/..." paths use `getExternalStoragePublicDirectory(DIRECTORY_DOCUMENTS)` on Android 10+ so writes succeed.
- Boox device: enable USB Debug Mode and allow USB debugging when prompted for `installDebug`.
- Text recognition (MyScript/InboxSyncEngine): line proximity grouping must run before bullet/list prefix detection; only the first line of a multi-line bullet has a dash — continuation lines do not.
- See "Always" above: install after app changes.

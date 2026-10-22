# Native app schema export

`NativeAppDatabase` is a new Room database, version 1, named `lnt_native_app.db`.
It owns only local project instance registrations in this slice. The legacy Room v6
database and native `.lnt` schema are separate and must not be opened by this database.

KSP exports the authoritative schema here when PC/CI compiles `:core:storage`.
`com.breakyuna.noveltranslator.nativecore.storage.NativeAppDatabase/1.json` is the
generated output of CI run 39789360824 (commit `224da53`), reviewed against
`NativeAppDatabase.kt` before committing. Do not hand-edit it: regenerate through a
PC/CI build, review the diff, then add the matching migration.

Native project SQL v1 and its format ID are in `NativeProjectDatabase.kt`; Room does not
manage that file. Changes to either schema require their own version/migration review.

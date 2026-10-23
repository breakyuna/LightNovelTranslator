# Native app schema export

`NativeAppDatabase` is a new Room database, version 3 in current source, named `lnt_native_app.db`.
It owns local project instance registrations plus native Agent messages, request receipts, structured conversations, model configuration and single-item approval payloads. MIGRATION_1_2 preserves the v1 index and adds these tables and structured message payload columns. The v2 runtime migration and generated implementation passed the CI recorded in HANDOFF section 26; the authoritative exported 2.json is still missing. The legacy Room v6
database and native `.lnt` schema are separate and must not be opened by this database.

KSP exports the authoritative schema here when PC/CI compiles `:core:storage`.
`com.breakyuna.noveltranslator.nativecore.storage.NativeAppDatabase/1.json` is the
generated output of CI run 39789360824 (commit `224da53`), reviewed against
`NativeAppDatabase.kt` before committing. Do not hand-edit it: regenerate through a
PC/CI build, review the diff, then add the matching migration.

Source v3 adds the native_runs execution journal with MIGRATION_2_3; v1 upgrades through both migrations. The v3 migration and recovery tests passed in the commit recorded in HANDOFF section 33; this is runtime test evidence, not exported-schema or device-upgrade acceptance.

The generated 2.json and 3.json are pending an authorized PC/CI KSP build and review; do not hand-write it or treat runtime migration tests as proof of an exported schema or device upgrade acceptance. Keep 1.json as the committed migration baseline.

Native project SQL v1 and its format ID are in `NativeProjectDatabase.kt`; Room does not
manage that file. Changes to either schema require their own version/migration review.

# Native app schema export

`NativeAppDatabase` is a new Room database, version 1, named `lnt_native_app.db`.
It owns only local project instance registrations in this slice. The legacy Room v6
database and native `.lnt` schema are separate and must not be opened by this database.

KSP exports the authoritative `1.json` here when PC/CI compiles `:core:storage`.
The test artifact includes this directory so the first generated schema can be reviewed
and committed before any v2 migration is introduced. No generated JSON has been fabricated
in Termux, where building is prohibited; initial schema export remains unverified.

Native project SQL v1 and its format ID are in `NativeProjectDatabase.kt`; Room does not
manage that file. Changes to either schema require their own version/migration review.

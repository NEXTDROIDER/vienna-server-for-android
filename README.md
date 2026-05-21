# Vienna Server Android

Android Kotlin port of the Vienna Rust API server.

The app starts an embedded HTTP server on the selected port and stores runtime data in:

`/data/data/com.vienna.server.android/files/vienna`

Main folders:

- `earth.db` - SQLite object database compatible with the Rust `EarthDb` layout.
- `data` - optional static data folder with `catalog`, `levels`, `tappables`, and `encounters`.
- `buildplates` - buildplate folders used by `/buildplates` and player grants.
- `logs` - files exposed by `/logs`.
- `resource-pack.zip` - optional CDN resource pack file.

Implemented endpoints include:

- `/`, `/health`, `/version`, `/auth/config`
- `/player/environment`, `/api/v1.1/player/environment`
- `/players/{playerId}/items`
- `/players/{playerId}/roles`
- `/buildplates`, `/players/{playerId}/buildplates`
- `/data/import`
- `/static/summary`, `/shop/catalog`, `/levels/{level}/rewards`
- `/tappables/generate`
- `/logs`

Build with Android Studio or Gradle after installing the Android Gradle plugin:

```powershell
.\gradlew.bat assembleDebug
```

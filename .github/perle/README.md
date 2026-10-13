# NewPipe Perle signing key

`perle-signing.keystore` signs the APKs published by `.github/workflows/perle-release.yml`
(alias `androiddebugkey`, password `android`, like an Android debug key).

It is committed on purpose: this fork has no other way to keep the same key between builds,
and Android only installs an update over an existing app if it is signed with the same key.
It is used only for NewPipe Perle, never for anything else.

Since the key is public, anyone could sign an APK that installs as an update of NewPipe Perle:
only install NewPipe Perle from the Releases of this repository.
To use a private key instead, store a base64 encoded keystore with the same alias and passwords
in the `PERLE_DEBUG_KEYSTORE` repository secret (the app then has to be reinstalled once).

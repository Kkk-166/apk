# apk

Builds the MIT-licensed Voice Studio Android APK from
https://github.com/ys05086/rvc-voice-studio-mobile on GitHub Actions.

Artifact: Actions → build-apk → android-apk.

The APK is signed with a throwaway debug key unless ANDROID_KEYSTORE_B64 /
ANDROID_KEYSTORE_PASSWORD secrets are configured.

# Maya AI Android

Maya AI is the Android voice version of the original Gemini Live Python assistant. It connects directly to Gemini Live over WebSocket, uses the phone microphone and plays Gemini audio responses.

## Build
GitHub Actions builds a debug APK automatically on push to `main`, or manually from Actions → Build Maya AI APK.

## Run
Install the generated APK, enter your Gemini API key, allow microphone permission, and tap START MAYA.

For personal testing only. Do not commit API keys to GitHub. For production, use a server-side or ephemeral-token authentication design.

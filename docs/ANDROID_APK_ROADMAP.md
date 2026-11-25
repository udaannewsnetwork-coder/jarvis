# SHADOW Android APK Roadmap

SHADOW is a personal AI agent project, separate from SWASTHYA SAATHI. The product architecture and phased feature plan live in [SHADOW Product Blueprint](SHADOW_PRODUCT_BLUEPRINT.md).

## Product target
- Professional native Android app with a cinematic dark interface and blue assistant orb.
- Hindi/English chat and voice, spoken replies, settings and conversation history.
- Shared backend for assistant reasoning, memory, research and safe tools.
- Honest connection and permission states; never show ONLINE unless a health check confirms it.
- Secure server-side AI credentials. No provider key is shipped inside the APK.
- Android-native capabilities added incrementally and permission-gated.

## Delivery phases
1. Stabilize and test the existing Next.js prototype and API routes.
2. Complete the native Android shell: chat UI, voice input, settings, history and lifecycle handling.
3. Connect the Android app to an authenticated shared assistant API.
4. Add durable conversation memory with user controls, export and deletion.
5. Add optional web research with source links and retrieval timestamps.
6. Add code/app creation in isolated build workers with diffs, logs and downloadable artifacts.
7. Add task tools with least privilege, previews, explicit approval, cancellation and audit history.
8. Add supported Android integrations such as TTS, reminders, share sheet and file picker.
9. Consider wake-word/background listening only after battery, privacy and Android permission behavior are tested.

## APK engineering requirements
- Do not embed provider API keys or signing passwords in source code or APK assets.
- Store local preferences and tokens using Android secure storage.
- Request microphone and notification permissions only when the feature is used.
- Keep a visible microphone/listening indicator and an immediate stop control.
- Do not silently install apps or execute generated code on the phone.
- Test on a real Android device before describing a build as device-tested.
- Use a user-owned signing key for release builds; never commit the key or passwords.

## Current implementation (verification status matters)
- Native Kotlin/Jetpack Compose app scaffold with SHADOW branding and animated orb.
- Microphone permission-gated speech recognition; recognized text is shown for review before sending.
- Chat UI posts to a configurable HTTPS backend URL; backend deployment and AI credentials are still required.
- Local chat history and response-language preference are stored in app-private preferences.
- On-device Android TextToSpeech prototype supports optional automatic spoken replies and a read-last/stop control. Voice quality and Hindi/English language availability depend on the device's installed TTS engine.
- SHADOW logo is integrated as a native vector launcher icon and Compose header mark.
- Tavily web research uses keyless Search mode when no server key is configured, with shared rate limits; an optional TAVILY_API_KEY uses the user's own server-side account. Android uses one chat composer: type `/search <query>` to request source titles, snippets and URLs in the conversation. Search snippets are not independently verified facts.
- User-controlled personal memory: up to 30 short notes are stored locally in app-private preferences. Users add and delete notes through the MEMORY control; the app sends those notes with chat requests, and the backend treats them as context rather than instructions. Memory notes are not automatically extracted or saved.
- The latest branding/research changes are committed to the development branch. Wait for the current GitHub Actions run before treating them as build-verified. This is not physical-device testing or proof that every planned feature is implemented.

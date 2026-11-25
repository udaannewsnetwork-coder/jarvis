# JARVIS Android APK Roadmap

This repository is the JARVIS assistant project. The Android application will be built as a dedicated Android client, sharing the assistant API and product identity without mixing this project with SWASTHYA SAATHI.

## Product target
- Professional Android experience with a polished dark, cinematic interface.
- Chat, Hindi/English voice input, spoken replies, settings and conversation history.
- Clear online/offline and microphone permission states; never show ONLINE unless a health check confirms it.
- Secure server-side AI credentials. No API key is shipped inside the APK.
- Android-native capabilities added incrementally and permission-gated.

## Delivery phases
1. Stabilize the existing Next.js assistant and API routes.
2. Add durable conversation memory with user controls and export/delete.
3. Add optional web research with source links and clear freshness indicators.
4. Add an Android client (native Kotlin preferred) with a shared backend contract.
5. Add wake-word/background listening only after battery, privacy and Android permission behavior are tested.
6. Add an explicit approval layer for actions that affect files, apps, accounts or device settings.
7. Build signed debug APKs in CI; prepare a release-signed APK only with a user-owned signing key.

## APK engineering requirements
- Do not embed provider API keys or signing passwords in source code or APK assets.
- Store local preferences and tokens using Android secure storage.
- Request microphone and notification permissions only when the feature is used.
- Keep a visible microphone/listening indicator and an immediate stop control.
- Test on a real Android device before describing a build as device-tested.

## Current limitation
The existing repository is a Next.js web prototype, not an Android project yet. A successful web build does not mean an APK exists. APK work begins after the shared API and UX foundations are stable.

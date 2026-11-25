# SHADOW — Personal AI Assistant

SHADOW is a cinematic personal AI assistant prototype built with Next.js, React and a server-side AI integration.

## Current prototype
- Futuristic dark assistant interface with a blue animated orb
- Text chat and browser-based voice input
- Audio recording and transcription endpoint
- Hindi and English interface/voice settings
- Spoken replies through browser speech synthesis
- Server-side API key handling

## Local setup
1. Install dependencies:
   `npm install`
2. Copy `.env.example` to `.env.local`.
3. Add your server-side provider key to `.env.local`.
4. Run `npm run dev` and open `http://localhost:3000`.

## Android direction
The Android APK will be a dedicated mobile client, not merely a website wrapper. It will use the shared backend, keep secrets out of the APK, request permissions only when needed, and include clear microphone/listening controls. See [Android APK roadmap](docs/ANDROID_APK_ROADMAP.md).

## Security
- Never commit real API keys.
- Keep provider secrets in server-side environment variables.
- Device actions will require explicit user approval.
- Do not describe a CI build as a real-device test.

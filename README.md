# JARVIS AI Assistant

A JARVIS-inspired AI assistant built with Next.js and OpenAI. This project is designed to be easy to deploy on Vercel.

## Features

- JARVIS-style chat interface
- Browser microphone input using SpeechRecognition
- Voice output using browser speech synthesis
- OpenAI-backed AI responses
- Simple Vercel deployment setup

## Quick start

1. Install dependencies:
   ```bash
   npm install
   ```

2. Copy `.env.example` to `.env.local` and add your OpenAI key:
   ```bash
   cp .env.example .env.local
   ```

3. Run the app locally:
   ```bash
   npm run dev
   ```

4. Open `http://localhost:3000`

## Vercel deployment

1. Push this repository to GitHub.
2. Import the repo in Vercel.
3. In the Vercel project settings, add an environment variable:
   - `OPENAI_API_KEY` = your OpenAI API key
4. Deploy the project.

## Notes

- Browser speech support depends on the device/browser. Chrome and Edge usually work best.
- For best voice experience, use a modern browser with microphone permission enabled.

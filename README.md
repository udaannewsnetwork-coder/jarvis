# JARVIS AI Assistant

A JARVIS-inspired AI assistant built with Next.js and OpenAI.

## Features
- JARVIS-style interface
- Type / speak / record input
- Audio transcription with Whisper
- AI responses via OpenAI
- Hindi and English support
- Vercel-ready deployment structure

## Local setup

1. Install dependencies:
   ```bash
   npm install
   ```

2. Create `.env.local` from `.env.example`:
   ```bash
   cp .env.example .env.local
   ```

3. Add your OpenAI API key:
   ```env
   OPENAI_API_KEY=sk-your-key-here
   ```

4. Start the app:
   ```bash
   npm run dev
   ```

5. Open `http://localhost:3000`

## Vercel deployment

1. Push the repo to GitHub.
2. Import the repo in Vercel.
3. In Project Settings → Environment Variables, add:
   - `OPENAI_API_KEY` = your OpenAI secret key
4. Redeploy.

## Security
- Do not commit real API keys to GitHub.
- Keep `.env.local` local only.
- Use Vercel environment variables for production deployments.

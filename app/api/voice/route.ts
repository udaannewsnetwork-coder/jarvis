import OpenAI from 'openai';
import { NextRequest } from 'next/server';

const client = new OpenAI({ apiKey: process.env.OPENAI_API_KEY });

export async function POST(request: NextRequest) {
  try {
    if (!process.env.OPENAI_API_KEY) {
      return Response.json(
        { error: 'Missing OPENAI_API_KEY in environment variables.' },
        { status: 500 }
      );
    }

    const form = await request.formData();
    const audio = form.get('audio') as Blob | null;
    const language = (form.get('language') as string) || 'hi';

    if (!audio) {
      return Response.json({ error: 'No audio file provided.' }, { status: 400 });
    }

    // Convert blob to buffer
    const arrayBuffer = await audio.arrayBuffer();
    const buffer = Buffer.from(arrayBuffer);

    // Transcribe audio using Whisper model
    const transcription = await client.audio.transcriptions.create({
      file: buffer,
      model: 'whisper-1',
      // language hint helps for better transcription accuracy
      // Note: the OpenAI SDK may accept `language` depending on version; include if supported
      ...(language ? { language } : {}),
    });

    const transcriptText = (transcription as any).text || '';

    // Now call chat completion with the transcript as the user's message
    const completion = await client.chat.completions.create({
      model: 'gpt-4o-mini',
      messages: [
        {
          role: 'system',
          content:
            'You are JARVIS, an advanced AI assistant. Be helpful, concise, and friendly. Reply in the same language as the user message.',
        },
        { role: 'user', content: transcriptText },
      ],
      temperature: 0.8,
      max_tokens: 500,
    });

    const reply = completion.choices[0]?.message?.content ?? 'I am here and ready to help.';

    return Response.json({ transcript: transcriptText, reply });
  } catch (error) {
    console.error('Voice API error:', error);
    return Response.json({ error: 'Failed to process audio.' }, { status: 500 });
  }
}

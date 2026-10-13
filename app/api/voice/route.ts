import OpenAI from 'openai';
import { NextRequest } from 'next/server';

const client = new OpenAI({ apiKey: process.env.OPENAI_API_KEY });

export async function POST(request: NextRequest) {
  try {
    if (!process.env.OPENAI_API_KEY) {
      return Response.json({ error: 'Missing OPENAI_API_KEY in environment variables.' }, { status: 500 });
    }

    const formData = await request.formData();
    const audio = formData.get('audio') as Blob | null;
    const language = (formData.get('language') as string) || 'hi';

    if (!audio) {
      return Response.json({ error: 'No audio file provided.' }, { status: 400 });
    }

    const arrayBuffer = await audio.arrayBuffer();
    const buffer = Buffer.from(arrayBuffer);

    const transcription = await client.audio.transcriptions.create({
      file: new File([buffer], 'recording.webm', { type: audio.type || 'audio/webm' }),
      model: 'whisper-1',
      language,
    });

    const transcript = transcription.text || '';

    const completion = await client.chat.completions.create({
      model: 'gpt-4o-mini',
      messages: [
        {
          role: 'system',
          content:
            'You are JARVIS, an advanced AI assistant. Be helpful, concise, and friendly. Respond in the same language as the user query.',
        },
        { role: 'user', content: transcript },
      ],
      temperature: 0.8,
      max_tokens: 500,
    });

    const reply = completion.choices[0]?.message?.content || 'I am here and ready to help.';

    return Response.json({ transcript, reply });
  } catch (error) {
    console.error('Voice route error:', error);
    return Response.json({ error: 'Failed to process audio file.' }, { status: 500 });
  }
}

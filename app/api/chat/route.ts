import OpenAI from 'openai';
import { NextRequest } from 'next/server';

const client = new OpenAI({
  apiKey: process.env.OPENAI_API_KEY,
});

export async function POST(request: NextRequest) {
  try {
    if (!process.env.OPENAI_API_KEY) {
      return Response.json({ error: 'Missing OPENAI_API_KEY in environment variables.' }, { status: 500 });
    }

    const body = await request.json();
    const { messages, language = 'hi' } = body || {};

    if (!Array.isArray(messages) || messages.length === 0) {
      return Response.json({ error: 'No chat messages provided.' }, { status: 400 });
    }

    const completion = await client.chat.completions.create({
      model: 'gpt-4o-mini',
      messages: [
        {
          role: 'system',
          content:
            'You are JARVIS, a cinematic personal assistant. Be helpful, concise, and confident. Respond naturally in the same language as the user. If the user writes in Hindi, reply in Hindi. If they speak English, reply in English.',
        },
        ...messages,
      ],
      temperature: 0.8,
      max_tokens: 500,
    });

    const reply = completion.choices[0]?.message?.content || 'I am here and ready to help.';

    return Response.json({ reply, language });
  } catch (error) {
    console.error('Chat route error:', error);
    return Response.json({ error: 'Failed to generate AI response.' }, { status: 500 });
  }
}

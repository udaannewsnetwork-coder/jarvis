import OpenAI from 'openai';
import { NextRequest } from 'next/server';

const client = new OpenAI({
  apiKey: process.env.OPENAI_API_KEY,
});

export async function POST(request: NextRequest) {
  try {
    const { messages } = await request.json();

    if (!process.env.OPENAI_API_KEY) {
      return Response.json(
        { error: 'Missing OPENAI_API_KEY in environment variables.' },
        { status: 500 }
      );
    }

    const chatMessages = Array.isArray(messages) ? messages : [{ role: 'user', content: 'Hello' }];

    const completion = await client.chat.completions.create({
      model: 'gpt-4o-mini',
      messages: [
        {
          role: 'system',
          content:
            'You are JARVIS, an advanced AI assistant inspired by a cinematic personal assistant. Be crisp, helpful, and confident. Reply naturally and concisely in a friendly tone.',
        },
        ...chatMessages,
      ],
      temperature: 0.8,
      max_tokens: 500,
    });

    const reply = completion.choices[0]?.message?.content ?? 'I am here and ready to help.';

    return Response.json({ reply });
  } catch (error) {
    console.error('Chat API error:', error);
    return Response.json(
      { error: 'Failed to generate a response from the AI model.' },
      { status: 500 }
    );
  }
}

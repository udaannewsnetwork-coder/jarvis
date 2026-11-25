import OpenAI from "openai";
import { NextRequest } from "next/server";

const MAX_MESSAGES = 30;
const MAX_MESSAGE_LENGTH = 6000;

export async function POST(request: NextRequest) {
  try {
    const apiKey = process.env.OPENAI_API_KEY;
    if (!apiKey) {
      return Response.json({ error: "AI service is not configured yet. Add OPENAI_API_KEY in your deployment environment." }, { status: 503 });
    }

    const body = await request.json();
    const messages = body?.messages;
    const language = body?.language === "en" ? "en" : "hi";
    if (!Array.isArray(messages) || messages.length === 0 || messages.length > MAX_MESSAGES) {
      return Response.json({ error: "Please send between 1 and 30 chat messages." }, { status: 400 });
    }

    const safeMessages: Array<{ role: "user" | "assistant"; content: string }> = [];
    for (const item of messages) {
      if (!item || (item.role !== "user" && item.role !== "assistant") ||
          typeof item.content !== "string" || item.content.trim().length === 0 ||
          item.content.length > MAX_MESSAGE_LENGTH) {
        return Response.json({ error: "A message is invalid or too long." }, { status: 400 });
      }
      safeMessages.push({ role: item.role, content: item.content.trim() });
    }

    const client = new OpenAI({ apiKey });
    const completion = await client.chat.completions.create({
      model: process.env.OPENAI_CHAT_MODEL || "gpt-4o-mini",
      messages: [
        { role: "system", content: "You are JARVIS, a reliable personal AI assistant. Be helpful, accurate, calm and concise. Reply in the same language as the latest user message. Never claim to have performed an action unless a connected tool actually performed it. If uncertain, say so." },
        ...safeMessages
      ],
      temperature: 0.6,
      max_tokens: 700
    });

    return Response.json({ reply: completion.choices[0]?.message?.content || "I am ready to help.", language });
  } catch (error) {
    console.error("Chat route error:", error);
    return Response.json({ error: "JARVIS could not complete that request. Please try again." }, { status: 500 });
  }
}

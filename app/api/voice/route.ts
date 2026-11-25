import OpenAI from "openai";
import { NextRequest } from "next/server";

const MAX_AUDIO_BYTES = 20 * 1024 * 1024;

export async function POST(request: NextRequest) {
  try {
    const apiKey = process.env.OPENAI_API_KEY;
    if (!apiKey) {
      return Response.json({ error: "Voice AI is not configured yet. Add OPENAI_API_KEY in your deployment environment." }, { status: 503 });
    }

    const formData = await request.formData();
    const audio = formData.get("audio");
    const languageValue = formData.get("language");
    const language = languageValue === "en" ? "en" : "hi";

    if (!(audio instanceof Blob) || audio.size === 0) {
      return Response.json({ error: "Please record audio before sending." }, { status: 400 });
    }
    if (audio.size > MAX_AUDIO_BYTES) {
      return Response.json({ error: "Audio is too large. Please keep recordings under 20 MB." }, { status: 413 });
    }

    const client = new OpenAI({ apiKey });
    const buffer = Buffer.from(await audio.arrayBuffer());
    const transcription = await client.audio.transcriptions.create({
      file: new File([buffer], "recording.webm", { type: audio.type || "audio/webm" }),
      model: process.env.OPENAI_TRANSCRIPTION_MODEL || "whisper-1",
      language
    });
    const transcript = transcription.text?.trim() || "";
    if (!transcript) {
      return Response.json({ error: "No speech was detected. Please try again." }, { status: 422 });
    }

    const completion = await client.chat.completions.create({
      model: process.env.OPENAI_CHAT_MODEL || "gpt-4o-mini",
      messages: [
        { role: "system", content: "You are JARVIS, a reliable personal AI assistant. Reply naturally in the same language as the user. Be accurate and concise." },
        { role: "user", content: transcript }
      ],
      temperature: 0.6,
      max_tokens: 700
    });
    return Response.json({ transcript, reply: completion.choices[0]?.message?.content || "I am ready to help." });
  } catch (error) {
    console.error("Voice route error:", error);
    return Response.json({ error: "Voice processing failed. Please try a shorter recording and try again." }, { status: 500 });
  }
}

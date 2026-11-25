import OpenAI from "openai";
import { NextRequest } from "next/server";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const MAX_BRIEF_LENGTH = 2000;
const MAX_FILES = 10;
const MAX_TOTAL_FILE_CHARS = 40000;

function safeProjectPath(value: unknown): value is string {
  if (typeof value !== "string" || value.length < 1 || value.length > 160) return false;
  if (value.startsWith("/") || value.includes("\\\\" ) || value.includes("..")) return false;
  return /^[a-zA-Z0-9._/-]+$/.test(value) && !value.split("/").some((part) => !part || part === "." || part === "..");
}

export async function POST(request: NextRequest) {
  try {
    const apiKey = process.env.OPENAI_API_KEY;
    if (!apiKey) return Response.json({ error: "AI builder is not configured yet. Add OPENAI_API_KEY in your deployment environment." }, { status: 503 });
    const body = await request.json();
    const brief = typeof body?.description === "string" ? body.description.trim() : "";
    const language = body?.language === "en" ? "English" : "Hindi/Hinglish";
    if (!brief || brief.length > MAX_BRIEF_LENGTH) return Response.json({ error: "Describe the app or website in 1–2,000 characters." }, { status: 400 });

    const client = new OpenAI({ apiKey });
    const completion = await client.chat.completions.create({
      model: process.env.OPENAI_CHAT_MODEL || "gpt-4o-mini",
      response_format: { type: "json_object" },
      temperature: 0.3,
      max_tokens: 5000,
      messages: [
        { role: "system", content: [
          "You are SHADOW BUILDER, a careful senior full-stack engineer.",
          "Create a beginner-friendly starter project from the brief. Prefer free, lightweight, maintainable technologies.",
          "Return ONLY valid JSON: { projectName: string, summary: string, stack: string[], files: [{ path: string, content: string }] }.",
          "Generate 2 to 10 essential files with useful starter code, not TODO-only placeholders. Keep total source under 40,000 characters.",
          "Paths must be relative, safe project paths. Never use absolute paths, traversal, hidden credential files, or binary files.",
          "Never include real API keys, passwords, private signing keys, destructive scripts, malware, credential theft, or covert tracking.",
          "Use environment variable placeholders for secrets. For web projects include package.json and a clear entry point.",
          "Use " + language + " for explanations and comments; keep code identifiers conventional.",
          "This is a source preview only. Never claim files were saved, executed, tested, deployed, or packaged into an APK."
        ].join(" ") },
        { role: "user", content: brief }
      ]
    });
    const raw = completion.choices[0]?.message?.content;
    if (!raw) return Response.json({ error: "The builder returned an empty project. Please refine the description." }, { status: 502 });
    let generated: any;
    try { generated = JSON.parse(raw); } catch { return Response.json({ error: "The builder response could not be read. Please try again." }, { status: 502 }); }
    const inputFiles = Array.isArray(generated?.files) ? generated.files : [];
    if (inputFiles.length < 2 || inputFiles.length > MAX_FILES) return Response.json({ error: "The generated project did not contain a valid number of files." }, { status: 502 });
    const seen = new Set<string>();
    let totalChars = 0;
    const files: Array<{ path: string; content: string }> = [];
    for (const item of inputFiles) {
      if (!safeProjectPath(item?.path) || typeof item?.content !== "string" || seen.has(item.path)) return Response.json({ error: "The generated project contained an invalid or duplicate file path." }, { status: 502 });
      seen.add(item.path);
      totalChars += item.content.length;
      if (item.content.length > 12000 || totalChars > MAX_TOTAL_FILE_CHARS) return Response.json({ error: "The source package is too large. Ask for a smaller starter project." }, { status: 502 });
      files.push({ path: item.path, content: item.content });
    }
    const projectName = String(generated.projectName || "shadow-project").slice(0, 80);
    const summary = String(generated.summary || "Starter project generated.").slice(0, 1000);
    const stack = Array.isArray(generated.stack) ? generated.stack.slice(0, 8).map((x: unknown) => String(x).slice(0, 80)) : [];
    const tree = files.map((file) => "  " + file.path).join("\\n");
    const source = files.map((file) => "### " + file.path + "\\n\\n" + String.fromCharCode(96,96,96) + "\\n" + file.content + "\\n" + String.fromCharCode(96,96,96)).join("\\n\\n");
    const reply = ["SHADOW BUILDER • SOURCE PREVIEW", "", "Project: " + projectName, "Summary: " + summary, "Stack: " + (stack.join(", ") || "Not specified"), "", "FILE TREE", tree, "", "GENERATED SOURCE FILES", source, "", "NEXT STEP: This is a source preview. SHADOW has not saved these files to your phone, run the project, or built an APK yet. Project export and build automation are future steps."].join("\\n");
    return Response.json({ projectName, summary, stack, files, reply });
  } catch (error) {
    console.error("SHADOW builder route error:", error);
    return Response.json({ error: "SHADOW Builder could not generate this project. Please try a shorter description." }, { status: 500 });
  }
}

import { NextRequest } from "next/server";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const MAX_QUERY_LENGTH = 300;

type ResearchResult = { title: string; url: string; snippet: string };

function isHttpUrl(value: unknown): value is string {
  if (typeof value !== "string") return false;
  try {
    const parsed = new URL(value);
    return parsed.protocol === "https:" || parsed.protocol === "http:";
  } catch {
    return false;
  }
}

export async function POST(request: NextRequest) {
  try {
    const body = await request.json();
    const query = typeof body?.query === "string" ? body.query.trim() : "";
    if (!query || query.length > MAX_QUERY_LENGTH) {
      return Response.json({ error: "Enter a research query under 300 characters." }, { status: 400 });
    }

    const apiKey = process.env.TAVILY_API_KEY;
    // Tavily Search supports a rate-limited keyless mode. Keep credentials server-side
    // when a user later configures a key; never send a provider key to the Android app.
    const headers: Record<string, string> = {
      "Content-Type": "application/json",
      "Accept": "application/json",
      "X-Client-Source": "shadow-android"
    };
    if (apiKey) headers.Authorization = `Bearer ${apiKey}`;
    else headers["X-Tavily-Access-Mode"] = "keyless";

    const response = await fetch("https://api.tavily.com/search", {
      method: "POST",
      headers,
      body: JSON.stringify({ query, search_depth: "basic", max_results: 5, include_answer: false }),
      signal: AbortSignal.timeout(15000),
      cache: "no-store"
    });

    if (!response.ok) {
      const status = response.status;
      console.error("SHADOW Tavily provider error:", status);
      if (status === 429) {
        return Response.json({
          error: apiKey
            ? "Tavily usage limit reached. Check your Tavily account credits."
            : "Tavily's shared free keyless limit has been reached for now. Try again later or add your own free Tavily API key in the server environment."
        }, { status: 429 });
      }
      return Response.json({ error: "Tavily web search is temporarily unavailable. Please try again." }, { status: 502 });
    }

    const data = await response.json();
    const results: ResearchResult[] = Array.isArray(data?.results)
      ? data.results.slice(0, 5).filter((item: any) => isHttpUrl(item?.url)).map((item: any) => ({
          title: String(item.title || "Untitled source").slice(0, 180),
          url: item.url,
          snippet: String(item.content || "").slice(0, 700)
        }))
      : [];

    return Response.json({
      provider: "Tavily",
      mode: apiKey ? "web_search" : "web_search_keyless",
      results,
      notice: apiKey
        ? "Tavily web results are source material, not independently verified facts. Open sources to confirm important details."
        : "Tavily keyless Search is free but shared and rate-limited. Results are source material, not independently verified facts."
    });
  } catch (error) {
    console.error("SHADOW research route error:", error);
    return Response.json({ error: "SHADOW research could not complete the request. Please try again." }, { status: 500 });
  }
}

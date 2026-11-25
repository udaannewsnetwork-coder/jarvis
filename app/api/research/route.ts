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
    if (apiKey) {
      const response = await fetch("https://api.tavily.com/search", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Authorization": `Bearer ${apiKey}`
        },
        body: JSON.stringify({ query, search_depth: "basic", max_results: 5, include_answer: false }),
        signal: AbortSignal.timeout(15000),
        cache: "no-store"
      });
      if (!response.ok) {
        console.error("SHADOW research provider error:", response.status);
        return Response.json({ error: "The configured research provider is temporarily unavailable." }, { status: 502 });
      }
      const data = await response.json();
      const results: ResearchResult[] = Array.isArray(data?.results)
        ? data.results.slice(0, 5).filter((item: any) => isHttpUrl(item?.url)).map((item: any) => ({
            title: String(item.title || "Untitled source").slice(0, 180),
            url: item.url,
            snippet: String(item.content || "").slice(0, 700)
          }))
        : [];
      return Response.json({ provider: "Tavily", mode: "web_search", results,
        notice: "Search results are source material, not independently verified facts. Open sources to confirm important details." });
    }

    // Official DuckDuckGo Instant Answer endpoint is keyless but is not a full web search index.
    const params = new URLSearchParams({ q: query, format: "json", no_html: "1", skip_disambig: "1" });
    const response = await fetch(`https://api.duckduckgo.com/?${params.toString()}`, {
      headers: { "Accept": "application/json" },
      signal: AbortSignal.timeout(10000),
      cache: "no-store"
    });
    if (!response.ok) {
      return Response.json({ error: "The free instant-answer source is temporarily unavailable. Configure TAVILY_API_KEY for full web search." }, { status: 502 });
    }
    const data = await response.json();
    const results: ResearchResult[] = [];
    if (data?.AbstractURL && isHttpUrl(data.AbstractURL)) {
      results.push({
        title: String(data.Heading || "Topic overview").slice(0, 180),
        url: data.AbstractURL,
        snippet: String(data.AbstractText || "").slice(0, 700)
      });
    }
    const collectTopics = (topics: any[]) => {
      for (const topic of topics || []) {
        if (results.length >= 5) break;
        if (topic?.Topics) collectTopics(topic.Topics);
        else if (isHttpUrl(topic?.FirstURL)) {
          results.push({
            title: String(topic.Text || "Related topic").slice(0, 180),
            url: topic.FirstURL,
            snippet: String(topic.Text || "").slice(0, 500)
          });
        }
      }
    };
    collectTopics(data?.RelatedTopics || []);
    return Response.json({
      provider: "DuckDuckGo Instant Answer",
      mode: "instant_answer",
      results,
      notice: "Free no-key fallback is limited to topic summaries and related links; it is NOT full web search. Add TAVILY_API_KEY on the server for broader web results."
    });
  } catch (error) {
    console.error("SHADOW research route error:", error);
    return Response.json({ error: "SHADOW research could not complete the request. Please try again." }, { status: 500 });
  }
}

"use client";

import { FormEvent, useEffect, useRef, useState } from "react";

type Message = { role: "user" | "assistant"; content: string };
type AssistantMode = "idle" | "listening" | "thinking" | "speaking";

const welcome: Message[] = [
  { role: "assistant", content: "Hello. I am JARVIS. I am ready when you are." }
];

export default function HomePage() {
  const [messages, setMessages] = useState<Message[]>(welcome);
  const [input, setInput] = useState("");
  const [loading, setLoading] = useState(false);
  const [listening, setListening] = useState(false);
  const [recording, setRecording] = useState(false);
  const [language, setLanguage] = useState<"hi" | "en">("hi");
  const [chatOpen, setChatOpen] = useState(false);
  const [error, setError] = useState("");
  const [mode, setMode] = useState<AssistantMode>("idle");
  const recognitionRef = useRef<any>(null);
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const endRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, loading]);

  useEffect(() => () => {
    recognitionRef.current?.stop();
    mediaRecorderRef.current?.stop();
    streamRef.current?.getTracks().forEach((track) => track.stop());
    if (typeof window !== "undefined" && "speechSynthesis" in window) window.speechSynthesis.cancel();
  }, []);

  const speak = (text: string) => {
    if (!("speechSynthesis" in window)) return;
    window.speechSynthesis.cancel();
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = language === "hi" ? "hi-IN" : "en-IN";
    utterance.onstart = () => setMode("speaking");
    utterance.onend = () => setMode("idle");
    utterance.onerror = () => setMode("idle");
    window.speechSynthesis.speak(utterance);
  };

  const startListening = () => {
    setError("");
    const SpeechRecognitionCtor = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
    if (!SpeechRecognitionCtor) {
      setError("Live speech recognition is not supported here. Use the microphone recording option.");
      return;
    }
    try {
      const recognition = new SpeechRecognitionCtor();
      recognition.lang = language === "hi" ? "hi-IN" : "en-IN";
      recognition.continuous = false;
      recognition.interimResults = true;
      recognition.onstart = () => { setListening(true); setMode("listening"); };
      recognition.onend = () => { setListening(false); setMode("idle"); };
      recognition.onerror = (event: any) => {
        setListening(false); setMode("idle");
        setError(event?.error === "not-allowed" ? "Microphone permission was denied." : "Voice recognition stopped. Please try again.");
      };
      recognition.onresult = (event: any) => {
        const result = event.results[event.results.length - 1];
        if (result?.[0]?.transcript) setInput(result[0].transcript);
      };
      recognitionRef.current = recognition;
      recognition.start();
    } catch {
      setError("Could not start the microphone. Please check browser permissions.");
      setMode("idle");
    }
  };

  const stopListening = () => {
    recognitionRef.current?.stop();
    setListening(false);
    setMode("idle");
  };

  const sendAudio = async (blob: Blob) => {
    setLoading(true); setMode("thinking"); setError("");
    const pending: Message = { role: "user", content: "Voice message" };
    setMessages((prev) => [...prev, pending]);
    try {
      const formData = new FormData();
      formData.append("audio", blob, "recording.webm");
      formData.append("language", language);
      const response = await fetch("/api/voice", { method: "POST", body: formData });
      const data = await response.json();
      if (!response.ok) throw new Error(data.error || "Voice request failed.");
      const transcript = data.transcript || "Voice message";
      const reply = data.reply || "I am ready to help.";
      setMessages((prev) => {
        const next = [...prev];
        for (let i = next.length - 1; i >= 0; i--) {
          if (next[i].content === "Voice message" && next[i].role === "user") { next[i] = { role: "user", content: transcript }; break; }
        }
        return [...next, { role: "assistant", content: reply }];
      });
      setChatOpen(true);
      speak(reply);
    } catch (err) {
      const message = err instanceof Error ? err.message : "Voice processing failed.";
      setError(message);
      setMessages((prev) => [...prev, { role: "assistant", content: message }]);
      setMode("idle");
    } finally { setLoading(false); }
  };

  const toggleRecording = async () => {
    if (recording) {
      mediaRecorderRef.current?.stop();
      setRecording(false);
      return;
    }
    setError("");
    if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === "undefined") {
      setError("Audio recording is not supported in this browser.");
      return;
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      streamRef.current = stream;
      const mime = MediaRecorder.isTypeSupported("audio/webm") ? "audio/webm" : "";
      const recorder = new MediaRecorder(stream, mime ? { mimeType: mime } : undefined);
      chunksRef.current = [];
      recorder.ondataavailable = (event) => { if (event.data.size) chunksRef.current.push(event.data); };
      recorder.onstop = async () => {
        const blob = new Blob(chunksRef.current, { type: recorder.mimeType || "audio/webm" });
        stream.getTracks().forEach((track) => track.stop());
        streamRef.current = null;
        if (blob.size) await sendAudio(blob);
      };
      recorder.onerror = () => {
        setError("Recording failed. Please try again.");
        stream.getTracks().forEach((track) => track.stop());
        setRecording(false); setMode("idle");
      };
      mediaRecorderRef.current = recorder;
      recorder.start();
      setRecording(true); setMode("listening");
    } catch {
      setError("Microphone permission is needed to record your voice.");
      setRecording(false); setMode("idle");
    }
  };

  const submitChat = async (event: FormEvent) => {
    event.preventDefault();
    const text = input.trim();
    if (!text || loading) return;
    const next = [...messages, { role: "user" as const, content: text }];
    setMessages(next); setInput(""); setLoading(true); setMode("thinking"); setError(""); setChatOpen(true);
    try {
      const response = await fetch("/api/chat", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ messages: next, language })
      });
      const data = await response.json();
      if (!response.ok) throw new Error(data.error || "AI service request failed.");
      const reply = data.reply || "I am ready to help.";
      setMessages([...next, { role: "assistant", content: reply }]);
      speak(reply);
    } catch (err) {
      const message = err instanceof Error ? err.message : "Could not reach the AI service.";
      setError(message);
      setMessages([...next, { role: "assistant", content: message }]);
      setMode("idle");
    } finally { setLoading(false); }
  };

  const modeLabel = recording || listening ? "LISTENING" : loading ? "THINKING" : mode === "speaking" ? "SPEAKING" : "READY";

  return (
    <main className="assistant-screen">
      <div className="ambient ambient-one" />
      <div className="ambient ambient-two" />
      <header className="app-header">
        <div className="wordmark"><span className="wordmark-mark">J</span><span>JARVIS</span></div>
        <div className="header-actions">
          <span className={`system-status ${loading || listening || recording ? "active" : ""}`}><i />{modeLabel}</span>
          <button className="icon-button language-button" onClick={() => setLanguage((v) => v === "hi" ? "en" : "hi")} aria-label="Change language">{language === "hi" ? "HI" : "EN"}</button>
        </div>
      </header>

      <section className="hero">
        <div className="eyebrow"><span /> PERSONAL AI ASSISTANT <span /></div>
        <h1>Good to have you <em>back.</em></h1>
        <p className="hero-subtitle">Your world, connected. What can I help you with?</p>

        <button
          className={`orb-control ${listening || recording ? "is-listening" : ""} ${loading ? "is-thinking" : ""} ${mode === "speaking" ? "is-speaking" : ""}`}
          onClick={recording ? () => void toggleRecording() : listening ? stopListening : startListening}
          disabled={loading}
          aria-label={listening || recording ? "Stop listening" : "Start voice input"}
        >
          <span className="orb-halo halo-a" /><span className="orb-halo halo-b" /><span className="orb-halo halo-c" />
          <span className="orb-core"><span className="orb-shape" /></span>
          <span className="orb-spark spark-a" /><span className="orb-spark spark-b" />
        </button>

        <div className="voice-caption">
          <span className="voice-caption-main">{recording || listening ? "I'm listening" : loading ? "One moment" : mode === "speaking" ? "Speaking" : "Tap the core to speak"}</span>
          <span className="voice-caption-sub">{recording ? "Tap again when you are finished" : listening ? "Speak naturally · Hindi or English" : loading ? "JARVIS is processing your request" : "Or type your message below"}</span>
        </div>

        <div className="quick-actions">
          <button onClick={() => setChatOpen((v) => !v)} className={chatOpen ? "quick-action selected" : "quick-action"}><span className="quick-icon">⌘</span> Conversation</button>
          <button onClick={() => setLanguage((v) => v === "hi" ? "en" : "hi")} className="quick-action"><span className="quick-icon">文</span> {language === "hi" ? "हिन्दी" : "English"}</button>
          <button onClick={() => { setMessages(welcome); setChatOpen(false); setError(""); }} className="quick-action"><span className="quick-icon">↺</span> New chat</button>
        </div>
      </section>

      {chatOpen && (
        <section className="conversation-panel" aria-label="Conversation">
          <div className="conversation-heading"><span>CONVERSATION</span><button onClick={() => setChatOpen(false)} aria-label="Close conversation">×</button></div>
          <div className="conversation-messages">
            {messages.map((message, index) => <div key={index} className={`chat-line ${message.role}`}><span>{message.role === "assistant" ? "J" : "YOU"}</span><p>{message.content}</p></div>)}
            {loading && <div className="chat-line assistant"><span>J</span><p className="typing">Thinking…</p></div>}
            <div ref={endRef} />
          </div>
        </section>
      )}

      <footer className="composer-area">
        {error && <div className="error-note" role="alert">{error}</div>}
        <form className="composer" onSubmit={submitChat}>
          <input value={input} onChange={(event) => setInput(event.target.value)} placeholder="Message JARVIS…" aria-label="Message JARVIS" />
          <button type="button" className={recording ? "mic-button recording" : "mic-button"} onClick={() => void toggleRecording()} disabled={loading} aria-label={recording ? "Stop recording" : "Record voice"}>{recording ? "■" : "●"}</button>
          <button type="submit" className="send-button" disabled={loading || !input.trim()} aria-label="Send message">↑</button>
        </form>
        <p className="privacy-note"><span /> Your assistant is ready · Microphone activates only when you tap</p>
      </footer>
    </main>
  );
}

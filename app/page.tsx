"use client";

import { FormEvent, useEffect, useRef, useState } from 'react';

type Message = {
  role: 'user' | 'assistant';
  content: string;
};

const starterMessages: Message[] = [
  {
    role: 'assistant',
    content:
      'Hello. I am JARVIS. Ask me anything, or speak using the microphone and I will respond with voice.',
  },
];

export default function HomePage() {
  const [messages, setMessages] = useState<Message[]>(starterMessages);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [listening, setListening] = useState(false);
  const messageEndRef = useRef<HTMLDivElement | null>(null);
  const recognitionRef = useRef<any>(null);

  useEffect(() => {
    messageEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, loading]);

  const speak = (text: string) => {
    if (typeof window === 'undefined' || !('speechSynthesis' in window)) return;
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = 'en-IN';
    window.speechSynthesis.cancel();
    window.speechSynthesis.speak(utterance);
  };

  const startListening = () => {
    const SpeechRecognitionCtor =
      (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;

    if (!SpeechRecognitionCtor) {
      alert('Speech recognition is not supported in this browser.');
      return;
    }

    const recognition = new SpeechRecognitionCtor();
    recognition.lang = 'en-IN';
    recognition.continuous = false;
    recognition.interimResults = false;

    recognition.onstart = () => setListening(true);
    recognition.onend = () => setListening(false);
    recognition.onerror = () => setListening(false);
    recognition.onresult = (event: any) => {
      const transcript = event.results[0][0].transcript;
      setInput(transcript);
    };

    recognitionRef.current = recognition;
    recognition.start();
  };

  const stopListening = () => {
    recognitionRef.current?.stop();
    setListening(false);
  };

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    const trimmed = input.trim();

    if (!trimmed || loading) {
      return;
    }

    const userMessage: Message = { role: 'user', content: trimmed };
    const nextMessages = [...messages, userMessage];
    setMessages(nextMessages);
    setInput('');
    setLoading(true);

    try {
      const res = await fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ messages: nextMessages }),
      });

      const data = await res.json();
      const reply = data.reply || 'I am here and ready to help.';
      const assistantMessage: Message = { role: 'assistant', content: reply };
      setMessages([...nextMessages, assistantMessage]);
      speak(reply);
    } catch (error) {
      const assistantMessage: Message = {
        role: 'assistant',
        content: 'I could not reach the AI service. Please check the OpenAI key and environment configuration.',
      };
      setMessages([...nextMessages, assistantMessage]);
    } finally {
      setLoading(false);
    }
  };

  return (
    <main className="app-shell">
      <header className="topbar">
        <div className="brand">
          <span className="brand-badge" />
          <span>JARVIS</span>
        </div>
        <div className="status-pill">
          <span className="status-dot" />
          Online
        </div>
      </header>

      <section className="chat-panel">
        <div className="messages">
          {messages.map((message, index) => (
            <div key={`${message.role}-${index}`} className={`message-row ${message.role}`}>
              <div className="message-bubble">{message.content}</div>
            </div>
          ))}

          {loading && (
            <div className="message-row assistant">
              <div className="message-bubble">Thinking...</div>
            </div>
          )}

          <div ref={messageEndRef} />
        </div>

        <form className="input-wrap" onSubmit={handleSubmit}>
          <input
            className="prompt-input"
            aria-label="Enter prompt"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder="Ask JARVIS anything..."
          />

          <button
            type="button"
            className="secondary-button"
            onClick={listening ? stopListening : startListening}
            disabled={loading}
          >
            {listening ? 'Stop' : 'Speak'}
          </button>

          <button type="submit" className="primary-button" disabled={loading || !input.trim()}>
            {loading ? 'Running...' : 'Send'}
          </button>
        </form>
      </section>
    </main>
  );
}

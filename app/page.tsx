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
      'Hello. I am JARVIS. Ask me anything, or speak using the microphone and I will respond with voice. You can record audio (Hindi/English) and I will transcribe using Whisper before replying.',
  },
];

export default function HomePage() {
  const [messages, setMessages] = useState<Message[]>(starterMessages);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [listening, setListening] = useState(false);
  const [recording, setRecording] = useState(false);
  const [language, setLanguage] = useState('hi');
  const messageEndRef = useRef<HTMLDivElement | null>(null);
  const recognitionRef = useRef<any>(null);
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const recordedChunksRef = useRef<Blob[]>([]);

  useEffect(() => {
    messageEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, loading]);

  const speak = (text: string) => {
    if (typeof window === 'undefined' || !('speechSynthesis' in window)) return;
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = language === 'hi' ? 'hi-IN' : 'en-IN';
    window.speechSynthesis.cancel();
    window.speechSynthesis.speak(utterance);
  };

  const startListening = () => {
    const SpeechRecognitionCtor =
      (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;

    if (!SpeechRecognitionCtor) {
      alert('Speech recognition is not supported in this browser. Use the Record button instead.');
      return;
    }

    const recognition = new SpeechRecognitionCtor();
    recognition.lang = language === 'hi' ? 'hi-IN' : 'en-IN';
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

  const startRecording = async () => {
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      alert('Media devices are not available in this browser.');
      return;
    }

    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const mediaRecorder = new MediaRecorder(stream);
      recordedChunksRef.current = [];

      mediaRecorder.ondataavailable = (e) => {
        if (e.data && e.data.size > 0) recordedChunksRef.current.push(e.data);
      };

      mediaRecorder.onstop = async () => {
        const blob = new Blob(recordedChunksRef.current, { type: 'audio/webm' });
        await sendAudio(blob);
        // stop all tracks
        stream.getTracks().forEach((t) => t.stop());
      };

      mediaRecorderRef.current = mediaRecorder;
      mediaRecorder.start();
      setRecording(true);
    } catch (err) {
      console.error('Recording error', err);
      alert('Unable to access microphone.');
    }
  };

  const stopRecording = () => {
    mediaRecorderRef.current?.stop();
    setRecording(false);
  };

  const sendAudio = async (blob: Blob) => {
    const userMessage: Message = { role: 'user', content: '[audio] Sent audio for transcription...' };
    setMessages((m) => [...m, userMessage]);
    setLoading(true);

    try {
      const fd = new FormData();
      fd.append('audio', blob, 'recording.webm');
      fd.append('language', language);

      const res = await fetch('/api/voice', {
        method: 'POST',
        body: fd,
      });

      const data = await res.json();
      const reply = data.reply || 'I could not generate a reply.';
      const transcript = data.transcript || '';

      // Replace the last '[audio]' user message with the actual transcript
      setMessages((prev) => {
        const copy = [...prev];
        const lastIndex = copy.map((x) => x.content).lastIndexOf('[audio] Sent audio for transcription...');
        if (lastIndex >= 0) copy[lastIndex] = { role: 'user', content: transcript || '[audio]' };
        return [...copy, { role: 'assistant', content: reply }];
      });

      speak(reply);
    } catch (err) {
      console.error('Voice error', err);
      setMessages((m) => [...m, { role: 'assistant', content: 'Failed to process audio.' }]);
    } finally {
      setLoading(false);
    }
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
        body: JSON.stringify({ messages: nextMessages, language }),
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

          <select
            value={language}
            onChange={(e) => setLanguage(e.target.value)}
            aria-label="Language"
            style={{ borderRadius: 12, padding: '8px 12px', minHeight: 56 }}
          >
            <option value="hi">Hindi</option>
            <option value="en">English</option>
          </select>

          <div style={{ display: 'flex', gap: 8 }}>
            <button
              type="button"
              className="secondary-button"
              onClick={recording ? stopRecording : startRecording}
              disabled={loading}
            >
              {recording ? 'Stop Rec' : 'Record'}
            </button>

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
          </div>
        </form>
      </section>
    </main>
  );
}

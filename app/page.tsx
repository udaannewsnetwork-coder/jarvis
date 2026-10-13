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
      'Hello. I am JARVIS. I can answer in Hindi or English. Speak or type your request and I will respond with voice.',
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

      mediaRecorder.ondataavailable = (evt) => {
        if (evt.data && evt.data.size > 0) recordedChunksRef.current.push(evt.data);
      };

      mediaRecorder.onstop = async () => {
        const blob = new Blob(recordedChunksRef.current, { type: 'audio/webm' });
        stream.getTracks().forEach((track) => track.stop());
        await sendAudio(blob);
      };

      mediaRecorderRef.current = mediaRecorder;
      mediaRecorder.start();
      setRecording(true);
    } catch (err) {
      console.error('Recording error:', err);
      alert('Unable to access the microphone. Please allow microphone permission and try again.');
    }
  };

  const stopRecording = () => {
    mediaRecorderRef.current?.stop();
    setRecording(false);
  };

  const sendAudio = async (blob: Blob) => {
    const stagedUserMessage: Message = { role: 'user', content: '[audio] Audio captured for transcription...' };
    setMessages((prev) => [...prev, stagedUserMessage]);
    setLoading(true);

    try {
      const formData = new FormData();
      formData.append('audio', blob, 'recording.webm');
      formData.append('language', language);

      const res = await fetch('/api/voice', {
        method: 'POST',
        body: formData,
      });

      const data = await res.json();

      if (!res.ok) {
        throw new Error(data.error || 'Failed to process audio.');
      }

      const transcript = data.transcript || '[audio]';
      const reply = data.reply || 'I am ready and listening.';

      setMessages((prev) => {
        const copy = [...prev];
        const lastIndex = copy.findLastIndex((item) => item.content === '[audio] Audio captured for transcription...');
        if (lastIndex >= 0) {
          copy[lastIndex] = { role: 'user', content: transcript || '[audio]' };
        }
        return [...copy, { role: 'assistant', content: reply }];
      });

      speak(reply);
    } catch (err) {
      console.error('Voice flow error:', err);
      setMessages((prev) => [...prev, { role: 'assistant', content: 'Audio processing failed. Please check your OpenAI key and try again.' }]);
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
      if (!res.ok) {
        throw new Error(data.error || 'Request failed.');
      }

      const reply = data.reply || 'I am here and ready to help.';
      setMessages([...nextMessages, { role: 'assistant', content: reply }]);
      speak(reply);
    } catch (error) {
      setMessages([
        ...nextMessages,
        {
          role: 'assistant',
          content:
            'I could not reach the AI service. Please verify your OpenAI API key and environment variables.',
        },
      ]);
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
          ONLINE
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
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder="Ask JARVIS anything..."
            aria-label="Chat prompt"
          />

          <select
            value={language}
            onChange={(e) => setLanguage(e.target.value)}
            aria-label="Language selector"
            style={{
              minHeight: 56,
              borderRadius: 12,
              padding: '0 12px',
              background: '#0d1a2a',
              color: '#e7f4ff',
              border: '1px solid rgba(117, 208, 255, 0.18)',
            }}
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

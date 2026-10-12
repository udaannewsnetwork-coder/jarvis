import './globals.css';
import type { Metadata } from 'next';

export const metadata: Metadata = {
  title: 'JARVIS | AI Assistant',
  description: 'A JARVIS-inspired AI assistant prototype built with Next.js and OpenAI.',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}

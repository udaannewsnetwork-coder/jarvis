import type { Metadata } from 'next';
import './globals.css';

export const metadata: Metadata = {
  title: 'JARVIS | AI Assistant',
  description: 'A cinematic JARVIS-inspired AI assistant prototype with voice support.',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}

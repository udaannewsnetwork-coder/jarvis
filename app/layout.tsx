import type { Metadata } from 'next';
import './globals.css';

export const metadata: Metadata = {
  title: 'SHADOW | AI Assistant',
  description: 'A cinematic SHADOW-inspired AI assistant prototype with voice support.',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}

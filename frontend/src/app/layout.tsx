import type { Metadata } from "next";
import { AppShell } from "@/components/app-shell";
import "./globals.css";

export const metadata: Metadata = {
  title: { default: "Spring Tech KB", template: "%s · Spring Tech KB" },
  description: "可溯源、版本感知的 Spring 技术检索与问答。",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="zh-CN" suppressHydrationWarning>
      <body className="antialiased"><AppShell>{children}</AppShell></body>
    </html>
  );
}

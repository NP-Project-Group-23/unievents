import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "UniEvents | Campus event booking",
  description: "Discover university events, reserve seats, and manage bookings in real time.",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en">
      <body className="min-h-full flex flex-col">{children}</body>
    </html>
  );
}

import { Header } from "@/components/layout/header";
import { Sidebar } from "@/components/layout/sidebar";

export default function DashboardLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <div className="flex min-h-screen w-full bg-background">
      <div className="hidden md:sticky md:top-0 md:block md:h-screen md:w-60 md:shrink-0">
        <Sidebar />
      </div>
      <div className="flex min-w-0 flex-1 flex-col bg-muted/20">
        <Header />
        <main className="mx-auto w-full max-w-7xl flex-1 p-4 sm:p-6">
          {children}
        </main>
      </div>
    </div>
  );
}

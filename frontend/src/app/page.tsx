import Link from "next/link";
import { Button } from "@/components/ui/button";
import { Bot, ArrowRight, CheckCircle2, Shield, Zap } from "lucide-react";
import { GitBranch } from "lucide-react";
import { githubLoginUrl } from "@/lib/api";

export default function Home() {
  return (
    <div className="flex min-h-screen flex-col">
      <header className="px-4 lg:px-6 h-14 flex items-center border-b bg-background/50 backdrop-blur-xl sticky top-0 z-50">
        <Link className="flex items-center justify-center gap-2" href="#">
          <Bot className="h-6 w-6" />
          <span className="font-semibold">CodePilot</span>
        </Link>
        <nav className="ml-auto flex gap-4 sm:gap-6">
          <Link className="text-sm font-medium hover:underline underline-offset-4" href="#features">
            Features
          </Link>
          <span className="text-sm font-medium text-muted-foreground">
            Pricing
          </span>
        </nav>
      </header>
      <main className="flex-1">
        <section className="w-full py-12 md:py-24 lg:py-32 xl:py-48">
          <div className="container px-4 md:px-6">
            <div className="flex flex-col items-center space-y-4 text-center">
              <div className="space-y-2">
                <h1 className="text-3xl font-bold tracking-tighter sm:text-4xl md:text-5xl lg:text-6xl/none">
                  AI Code Reviews that feel like magic
                </h1>
                <p className="mx-auto max-w-[700px] text-muted-foreground md:text-xl">
                  Automate your code review process with a Staff Engineer level AI. Catch bugs, improve security, and ship faster.
                </p>
              </div>
              <div className="space-x-4">
                <a href={githubLoginUrl}>
                  <Button className="h-11 px-8">
                    Sign in with GitHub <ArrowRight className="ml-2 h-4 w-4" />
                  </Button>
                </a>
                <Link href="/dashboard">
                  <Button variant="outline" className="h-11 px-8">
                    <GitBranch className="mr-2 h-4 w-4" /> Open dashboard
                  </Button>
                </Link>
              </div>
            </div>
          </div>
        </section>

        <section id="features" className="w-full py-12 md:py-24 lg:py-32 bg-muted/40 border-t">
          <div className="container px-4 md:px-6">
            <div className="grid gap-12 sm:grid-cols-2 lg:grid-cols-3">
              <div className="flex flex-col items-center space-y-4 text-center">
                <div className="flex h-16 w-16 items-center justify-center rounded-full bg-primary/10">
                  <Zap className="h-8 w-8 text-primary" />
                </div>
                <h3 className="text-xl font-bold">Lightning Fast</h3>
                <p className="text-muted-foreground">
                  Get feedback in seconds, not hours. Unblock your team and ship with confidence.
                </p>
              </div>
              <div className="flex flex-col items-center space-y-4 text-center">
                <div className="flex h-16 w-16 items-center justify-center rounded-full bg-primary/10">
                  <Shield className="h-8 w-8 text-primary" />
                </div>
                <h3 className="text-xl font-bold">Secure by Default</h3>
                <p className="text-muted-foreground">
                  Your code is analyzed securely. We never store or train on your private repositories.
                </p>
              </div>
              <div className="flex flex-col items-center space-y-4 text-center">
                <div className="flex h-16 w-16 items-center justify-center rounded-full bg-primary/10">
                  <CheckCircle2 className="h-8 w-8 text-primary" />
                </div>
                <h3 className="text-xl font-bold">Actionable Feedback</h3>
                <p className="text-muted-foreground">
                  Receive specific, context-aware suggestions that you can apply with one click.
                </p>
              </div>
            </div>
          </div>
        </section>
      </main>
      <footer className="flex flex-col gap-2 sm:flex-row py-6 w-full shrink-0 items-center px-4 md:px-6 border-t">
        <p className="text-xs text-muted-foreground">© 2024 CodePilot Inc. All rights reserved.</p>
        <nav className="sm:ml-auto flex gap-4 sm:gap-6">
          <Link className="text-xs hover:underline underline-offset-4" href="#">
            Terms of Service
          </Link>
          <Link className="text-xs hover:underline underline-offset-4" href="#">
            Privacy
          </Link>
        </nav>
      </footer>
    </div>
  );
}

import Link from "next/link";
import { ArrowRight, CheckCircle2, GitBranch, GitPullRequest, Lock, ShieldCheck, Terminal } from "lucide-react";
import { Button } from "@/components/ui/button";
import { githubLoginUrl } from "@/lib/api";

const workflowSteps = [
  {
    step: "01",
    title: "Repository & PR Sync",
    description:
      "Connect via GitHub OAuth, import repositories by immutable GitHub ID, and synchronize open, closed, and merged pull requests.",
    icon: GitBranch,
  },
  {
    step: "02",
    title: "Diff-Scoped Analysis",
    description:
      "Gemini evaluates only added and modified diff hunks inside untrusted-input delimiters, rejecting unanchored lines and malformed output.",
    icon: Terminal,
  },
  {
    step: "03",
    title: "Idempotent Delivery",
    description:
      "Jobs run asynchronously over PostgreSQL and Redis with SHA-256 deduplication and optional, feature-flagged GitHub comment publishing.",
    icon: GitPullRequest,
  },
];

const guarantees = [
  "Read-only on repository source code — never commits, pushes, or merges",
  "Encrypted GitHub OAuth tokens at rest with AES-GCM",
  "Strict tenant isolation across repositories, PRs, reviews, and findings",
  "CSRF-protected mutations and HMAC-verified GitHub webhooks",
];

export default function Home() {
  return (
    <div className="flex min-h-screen flex-col bg-background text-foreground">
      <header className="sticky top-0 z-40 border-b bg-background/90 backdrop-blur-sm">
        <div className="mx-auto flex h-14 max-w-6xl items-center justify-between px-4 sm:px-6">
          <Link href="/" className="flex items-center gap-2.5 font-semibold tracking-tight">
            <span className="flex h-7 w-7 items-center justify-center rounded-md bg-primary text-xs font-mono font-bold text-primary-foreground">
              CP
            </span>
            <span>CodePilot</span>
          </Link>

          <nav className="hidden items-center gap-6 text-sm text-muted-foreground md:flex">
            <a href="#workflow" className="transition-colors hover:text-foreground">
              Workflow
            </a>
            <a href="#security" className="transition-colors hover:text-foreground">
              Security
            </a>
            <Link href="/dashboard" className="transition-colors hover:text-foreground">
              Dashboard
            </Link>
          </nav>

          <div className="flex items-center gap-2.5">
            <Link href="/dashboard" className="hidden sm:inline-flex">
              <Button variant="ghost" size="sm">
                Open dashboard
              </Button>
            </Link>
            <a href={githubLoginUrl}>
              <Button size="sm">
                Sign in with GitHub
                <ArrowRight className="ml-1.5 h-3.5 w-3.5" />
              </Button>
            </a>
          </div>
        </div>
      </header>

      <main className="flex-1">
        <section className="mx-auto max-w-6xl px-4 py-14 sm:px-6 md:py-20">
          <div className="grid items-start gap-10 lg:grid-cols-12 lg:gap-12">
            <div className="space-y-6 lg:col-span-6">
              <p className="font-mono text-xs font-medium tracking-wider text-muted-foreground uppercase">
                GitHub Pull Request Review Platform
              </p>
              <h1 className="text-3xl font-semibold tracking-tight text-foreground sm:text-4xl lg:text-5xl lg:leading-[1.1]">
                Evidence-based code reviews for every pull request.
              </h1>
              <p className="max-w-xl text-base leading-relaxed text-muted-foreground sm:text-lg">
                CodePilot analyzes pull request diffs with structured Gemini validation, tracks asynchronous review jobs reliably, and surfaces actionable findings with file, line, impact, and suggested fix context.
              </p>
              <div className="flex flex-wrap items-center gap-3 pt-1">
                <a href={githubLoginUrl}>
                  <Button size="lg" className="h-10 px-5">
                    Sign in with GitHub
                    <ArrowRight className="ml-2 h-4 w-4" />
                  </Button>
                </a>
                <Link href="/dashboard">
                  <Button variant="outline" size="lg" className="h-10 px-5">
                    Open dashboard
                  </Button>
                </Link>
              </div>
            </div>

            <div className="lg:col-span-6">
              <div className="overflow-hidden rounded-lg border bg-card shadow-xs">
                <div className="flex items-center justify-between border-b bg-muted/40 px-4 py-2.5">
                  <div className="flex items-center gap-2">
                    <span className="font-mono text-xs text-muted-foreground">
                      src/auth/SessionFilter.java:42
                    </span>
                  </div>
                  <span className="font-mono text-[11px] text-muted-foreground">
                    Example Finding Format
                  </span>
                </div>
                <div className="space-y-4 p-4 sm:p-5">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="inline-flex items-center rounded-md border border-orange-500/30 bg-orange-500/10 px-2 py-0.5 font-mono text-[11px] font-medium text-orange-700 dark:text-orange-400">
                      HIGH
                    </span>
                    <span className="inline-flex items-center rounded-md border bg-muted px-2 py-0.5 font-mono text-[11px] font-medium text-muted-foreground">
                      SECURITY
                    </span>
                    <span className="ml-auto font-mono text-[11px] text-muted-foreground">
                      Confidence: HIGH
                    </span>
                  </div>
                  <div>
                    <h2 className="text-sm font-semibold">
                      Session identifier not rotated after OAuth callback
                    </h2>
                    <p className="mt-1 text-sm leading-relaxed text-muted-foreground">
                      Authentication completes without migrating the pre-login session ID, leaving authenticated sessions exposed to fixation if an attacker supplies a session cookie.
                    </p>
                  </div>
                  <div className="rounded-md border bg-muted/40 p-3">
                    <p className="mb-1.5 font-mono text-[11px] font-medium text-muted-foreground uppercase">
                      Suggested Fix
                    </p>
                    <pre className="overflow-x-auto font-mono text-xs leading-relaxed text-foreground">
                      <code>{`request.changeSessionId();`}</code>
                    </pre>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </section>

        <section id="workflow" className="border-t bg-muted/30">
          <div className="mx-auto max-w-6xl px-4 py-14 sm:px-6 md:py-16">
            <div className="mb-10 max-w-2xl space-y-2">
              <p className="font-mono text-xs font-medium tracking-wider text-muted-foreground uppercase">
                Architecture & Workflow
              </p>
              <h2 className="text-2xl font-semibold tracking-tight sm:text-3xl">
                Built for deterministic, auditable reviews
              </h2>
            </div>
            <div className="grid gap-6 md:grid-cols-3">
              {workflowSteps.map((item) => (
                <div key={item.step} className="rounded-lg border bg-card p-5">
                  <div className="mb-4 flex items-center justify-between">
                    <span className="flex h-9 w-9 items-center justify-center rounded-md border bg-muted/40 text-foreground">
                      <item.icon className="h-4 w-4" />
                    </span>
                    <span className="font-mono text-xs text-muted-foreground">{item.step}</span>
                  </div>
                  <h3 className="text-base font-semibold tracking-tight">{item.title}</h3>
                  <p className="mt-2 text-sm leading-relaxed text-muted-foreground">
                    {item.description}
                  </p>
                </div>
              ))}
            </div>
          </div>
        </section>

        <section id="security" className="border-t">
          <div className="mx-auto max-w-6xl px-4 py-14 sm:px-6 md:py-16">
            <div className="grid gap-8 rounded-lg border bg-card p-6 sm:p-8 lg:grid-cols-12 lg:items-center">
              <div className="space-y-3 lg:col-span-5">
                <div className="inline-flex items-center gap-2 rounded-md border bg-muted/40 px-2.5 py-1 font-mono text-xs text-muted-foreground">
                  <Lock className="h-3.5 w-3.5" />
                  Security & Control
                </div>
                <h2 className="text-2xl font-semibold tracking-tight">
                  Controlled by your team, isolated per tenant
                </h2>
                <p className="text-sm leading-relaxed text-muted-foreground">
                  Every repository, pull request, review, and finding is scoped to the authenticated repository owner. GitHub comment publishing is gated behind an explicit server configuration flag.
                </p>
              </div>
              <div className="grid gap-3 sm:grid-cols-2 lg:col-span-7">
                {guarantees.map((text) => (
                  <div key={text} className="flex items-start gap-2.5 rounded-md border bg-background p-3.5">
                    <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-emerald-600 dark:text-emerald-400" />
                    <span className="text-sm leading-snug text-foreground">{text}</span>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </section>
      </main>

      <footer className="border-t bg-background">
        <div className="mx-auto flex max-w-6xl flex-col items-center justify-between gap-3 px-4 py-6 text-xs text-muted-foreground sm:flex-row sm:px-6">
          <div className="flex items-center gap-2">
            <ShieldCheck className="h-3.5 w-3.5" />
            <span>© 2026 CodePilot. Read-only AI pull request review platform.</span>
          </div>
          <nav className="flex items-center gap-5">
            <a href="#workflow" className="hover:text-foreground">
              Workflow
            </a>
            <a href="#security" className="hover:text-foreground">
              Security
            </a>
            <Link href="/dashboard" className="hover:text-foreground">
              Dashboard
            </Link>
          </nav>
        </div>
      </footer>
    </div>
  );
}

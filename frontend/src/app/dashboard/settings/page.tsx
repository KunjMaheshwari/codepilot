"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { GitBranch, LogOut, Shield, Unplug } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { ErrorBanner } from "@/components/ui/empty-state";
import { Skeleton } from "@/components/ui/skeleton";
import { useToast } from "@/components/ui/toast";
import { api, apiErrorMessage, githubLoginUrl, type UserProfile } from "@/lib/api";

export default function SettingsPage() {
  const router = useRouter();
  const { toast } = useToast();
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [loading, setLoading] = useState(true);
  const [disconnected, setDisconnected] = useState(false);
  const [disconnecting, setDisconnecting] = useState(false);
  const [signingOut, setSigningOut] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [error, setError] = useState("");

  const loadProfile = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const me = await api.getMe();
      setProfile(me);
    } catch (reason: unknown) {
      setError(apiErrorMessage(reason));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    api
      .getMe()
      .then((me) => {
        if (!cancelled) setProfile(me);
      })
      .catch((reason: unknown) => {
        if (!cancelled) setError(apiErrorMessage(reason));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const disconnect = async () => {
    if (disconnecting) return;
    setDisconnecting(true);
    setError("");
    try {
      await api.disconnectGitHub();
      setConfirmOpen(false);
      setDisconnected(true);
      toast({
        title: "GitHub disconnected",
        description: "Your linked GitHub OAuth account has been removed from CodePilot.",
        variant: "success",
      });
    } catch (reason: unknown) {
      const msg = apiErrorMessage(reason);
      setError(msg);
      toast({ title: "Disconnect failed", description: msg, variant: "error" });
    } finally {
      setDisconnecting(false);
    }
  };

  const handleLogout = async () => {
    if (signingOut) return;
    setSigningOut(true);
    try {
      await api.logout();
    } catch {
      // Redirect even if session was already invalidated.
    } finally {
      router.push("/");
    }
  };

  return (
    <div className="max-w-3xl space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Settings</h1>
        <p className="text-sm text-muted-foreground">
          Manage your authenticated CodePilot session and GitHub OAuth connection.
        </p>
      </div>

      {error && <ErrorBanner message={error} onRetry={() => void loadProfile()} />}

      {loading ? (
        <div className="space-y-4">
          <Skeleton className="h-44 w-full rounded-lg" />
          <Skeleton className="h-40 w-full rounded-lg" />
        </div>
      ) : (
        <div className="space-y-4">
          <section className="rounded-lg border bg-card p-5">
            <div className="flex items-start justify-between gap-3">
              <div>
                <h2 className="text-base font-semibold tracking-tight">Account</h2>
                <p className="text-xs text-muted-foreground">
                  Session identity resolved by the backend OAuth session.
                </p>
              </div>
              <Badge variant="outline" className="font-mono text-[11px]">
                <Shield className="mr-1 h-3 w-3" />
                Session Active
              </Badge>
            </div>

            <dl className="mt-4 grid gap-3 border-t pt-4 text-sm sm:grid-cols-2">
              <div>
                <dt className="text-xs text-muted-foreground">Username</dt>
                <dd className="mt-0.5 font-mono text-sm font-medium">
                  {profile?.username || "—"}
                </dd>
              </div>
              <div>
                <dt className="text-xs text-muted-foreground">Email</dt>
                <dd className="mt-0.5 font-mono text-sm font-medium">
                  {profile?.email || "—"}
                </dd>
              </div>
            </dl>
          </section>

          <section className="rounded-lg border bg-card p-5">
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div className="space-y-1">
                <div className="flex items-center gap-2">
                  <GitBranch className="h-4 w-4 text-muted-foreground" />
                  <h2 className="text-base font-semibold tracking-tight">GitHub Connection</h2>
                </div>
                <p className="text-sm text-muted-foreground">
                  OAuth access tokens are encrypted at rest on the backend and never exposed to the browser.
                </p>
              </div>
              <Badge variant={!disconnected ? "default" : "secondary"}>
                {!disconnected ? "Linked" : "Disconnected"}
              </Badge>
            </div>

            {!disconnected && profile?.username && (
              <p className="mt-3 font-mono text-xs text-muted-foreground">
                Account login: @{profile.username}
              </p>
            )}

            <div className="mt-4 flex flex-wrap items-center gap-3 border-t pt-4">
              {!disconnected ? (
                <Dialog open={confirmOpen} onOpenChange={setConfirmOpen}>
                  <DialogTrigger
                    render={
                      <Button variant="destructive" size="sm">
                        <Unplug className="mr-1.5 h-3.5 w-3.5" />
                        Disconnect GitHub
                      </Button>
                    }
                  />
                  <DialogContent>
                    <DialogHeader>
                      <DialogTitle>Disconnect GitHub account?</DialogTitle>
                      <DialogDescription>
                        This removes your linked GitHub OAuth credentials from CodePilot. Repository synchronization and pull request reviews will pause until you reconnect.
                      </DialogDescription>
                    </DialogHeader>
                    <DialogFooter>
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={() => setConfirmOpen(false)}
                        disabled={disconnecting}
                      >
                        Cancel
                      </Button>
                      <Button
                        variant="destructive"
                        size="sm"
                        onClick={() => void disconnect()}
                        disabled={disconnecting}
                      >
                        {disconnecting ? "Disconnecting..." : "Confirm Disconnect"}
                      </Button>
                    </DialogFooter>
                  </DialogContent>
                </Dialog>
              ) : (
                <a href={githubLoginUrl}>
                  <Button size="sm">Reconnect GitHub</Button>
                </a>
              )}
            </div>
          </section>

          <section className="flex flex-wrap items-center justify-between gap-3 rounded-lg border bg-card p-5">
            <div>
              <h2 className="text-base font-semibold tracking-tight">Sign Out</h2>
              <p className="text-xs text-muted-foreground">
                Invalidate your current CodePilot session cookie.
              </p>
            </div>
            <Button
              variant="outline"
              size="sm"
              onClick={() => void handleLogout()}
              disabled={signingOut}
            >
              <LogOut className="mr-1.5 h-3.5 w-3.5" />
              {signingOut ? "Signing out..." : "Sign out"}
            </Button>
          </section>
        </div>
      )}
    </div>
  );
}

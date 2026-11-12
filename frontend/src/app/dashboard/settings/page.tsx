"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { api, apiErrorMessage } from "@/lib/api";

export default function SettingsPage() {
  const [message, setMessage] = useState("");
  const disconnect = () => {
    setMessage("");
    api.disconnectGitHub()
      .then(() => setMessage("GitHub account disconnected."))
      .catch((error: unknown) => setMessage(apiErrorMessage(error)));
  };

  return (
    <div className="flex flex-col gap-4 pt-6">
      <h1 className="text-3xl font-bold tracking-tight">Settings</h1>
      <section className="max-w-xl rounded-lg border bg-background p-5">
        <h2 className="font-semibold">GitHub connection</h2>
        <p className="mt-2 text-sm text-muted-foreground">
          Disconnecting removes the linked GitHub account from CodePilot. Access tokens are managed by the backend.
        </p>
        <Button className="mt-4" variant="destructive" onClick={disconnect}>Disconnect GitHub</Button>
        {message && <p className="mt-3 text-sm text-muted-foreground">{message}</p>}
      </section>
    </div>
  );
}

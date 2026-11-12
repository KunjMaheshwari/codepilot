"use client";

import { useEffect } from "react";
import { AlertCircle } from "lucide-react";
import { Button } from "@/components/ui/button";

export default function Error({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error(error);
  }, [error]);

  return (
    <div className="flex min-h-[60vh] w-full flex-col items-center justify-center gap-4 rounded-lg border border-dashed bg-card p-8 text-center">
      <div className="flex h-10 w-10 items-center justify-center rounded-lg border border-destructive/30 bg-destructive/10 text-destructive">
        <AlertCircle className="h-5 w-5" />
      </div>
      <div className="space-y-1.5">
        <h2 className="text-lg font-semibold tracking-tight">Unable to load workspace view</h2>
        <p className="max-w-md text-sm text-muted-foreground">
          An unexpected error occurred while rendering this page. You can retry loading the view.
        </p>
      </div>
      <Button onClick={() => reset()} size="sm">
        Try again
      </Button>
    </div>
  );
}

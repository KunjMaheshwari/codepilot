import { cn } from "@/lib/utils";

const severityStyles: Record<string, string> = {
  CRITICAL: "border-red-500/30 bg-red-500/10 text-red-700 dark:text-red-400",
  HIGH: "border-orange-500/30 bg-orange-500/10 text-orange-700 dark:text-orange-400",
  MEDIUM: "border-amber-500/30 bg-amber-500/10 text-amber-700 dark:text-amber-400",
  LOW: "border-sky-500/30 bg-sky-500/10 text-sky-700 dark:text-sky-400",
  INFO: "border-border bg-muted text-muted-foreground",
};

const statusStyles: Record<string, string> = {
  COMPLETED: "border-emerald-500/30 bg-emerald-500/10 text-emerald-700 dark:text-emerald-400",
  PARTIAL: "border-amber-500/30 bg-amber-500/10 text-amber-700 dark:text-amber-400",
  FAILED: "border-red-500/30 bg-red-500/10 text-red-700 dark:text-red-400",
  PROCESSING: "border-sky-500/30 bg-sky-500/10 text-sky-700 dark:text-sky-400",
  QUEUED: "border-border bg-secondary text-secondary-foreground",
  PENDING: "border-border bg-secondary text-secondary-foreground",
  RETRYING: "border-amber-500/30 bg-amber-500/10 text-amber-700 dark:text-amber-400",
  OPEN: "border-emerald-500/30 bg-emerald-500/10 text-emerald-700 dark:text-emerald-400",
  MERGED: "border-indigo-500/30 bg-indigo-500/10 text-indigo-700 dark:text-indigo-400",
  CLOSED: "border-border bg-muted text-muted-foreground",
};

export function StatusBadge({
  value,
  kind = "status",
  className,
}: {
  value: string;
  kind?: "status" | "severity";
  className?: string;
}) {
  const key = value.toUpperCase();
  const palette = kind === "severity" ? severityStyles : statusStyles;
  const style = palette[key] ?? "border-border bg-muted text-muted-foreground";

  return (
    <span
      className={cn(
        "inline-flex items-center rounded-md border px-2 py-0.5 font-mono text-[11px] font-medium tracking-tight uppercase",
        style,
        className,
      )}
    >
      {key}
    </span>
  );
}


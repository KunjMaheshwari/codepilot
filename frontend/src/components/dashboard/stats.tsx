"use client";

import { Activity, AlertTriangle, Bug, Code2 } from "lucide-react";
import type { DashboardStats as DashboardStatsData } from "@/lib/api";

export function DashboardStats({ stats }: { stats: DashboardStatsData }) {
  const items = [
    {
      label: "Total Reviews",
      value: stats.totalReviews,
      hint: "Across your account",
      icon: Code2,
      danger: false,
    },
    {
      label: "Issues Detected",
      value: stats.issuesDetected,
      hint: "Validated findings",
      icon: Bug,
      danger: false,
    },
    {
      label: "Critical Issues",
      value: stats.criticalIssues,
      hint: stats.criticalIssues > 0 ? "Requires immediate review" : "No critical findings",
      icon: AlertTriangle,
      danger: stats.criticalIssues > 0,
    },
    {
      label: "Active Repositories",
      value: stats.activeRepositories,
      hint: "Connected & active",
      icon: Activity,
      danger: false,
    },
  ];

  return (
    <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
      {items.map((item) => (
        <div
          key={item.label}
          className="rounded-lg border bg-card p-4 text-card-foreground"
        >
          <div className="flex items-center justify-between gap-2">
            <span className="text-xs font-medium text-muted-foreground">{item.label}</span>
            <item.icon
              className={`h-4 w-4 ${item.danger ? "text-destructive" : "text-muted-foreground"}`}
            />
          </div>
          <div
            className={`mt-2 font-mono text-2xl font-semibold tracking-tight tabular-nums ${
              item.danger ? "text-destructive" : "text-foreground"
            }`}
          >
            {item.value}
          </div>
          <p className="mt-1 text-xs text-muted-foreground">{item.hint}</p>
        </div>
      ))}
    </div>
  );
}

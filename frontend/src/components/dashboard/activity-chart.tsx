"use client";

import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import type { Review } from "@/lib/api";

export function ActivityChart({ reviews }: { reviews: Review[] }) {
  const activity = Array.from({ length: 7 }, (_, index) => {
    const date = new Date();
    date.setDate(date.getDate() - (6 - index));
    const key = date.toLocaleDateString();
    return {
      name: date.toLocaleDateString(undefined, { weekday: "short" }),
      reviews: reviews.filter(
        (review) => review.createdAt && new Date(review.createdAt).toLocaleDateString() === key,
      ).length,
    };
  });

  const hasActivity = activity.some((point) => point.reviews > 0);

  return (
    <div className="flex h-full flex-col rounded-lg border bg-card p-5 text-card-foreground">
      <div className="mb-4 flex items-center justify-between gap-2">
        <div>
          <h2 className="text-sm font-semibold tracking-tight">7-Day Review Activity</h2>
          <p className="text-xs text-muted-foreground">
            Reviews triggered over the last 7 days
          </p>
        </div>
      </div>

      {!hasActivity ? (
        <div className="flex flex-1 flex-col items-center justify-center rounded-md border border-dashed py-14 text-center">
          <p className="text-sm font-medium text-foreground">No review activity in the last 7 days</p>
          <p className="mt-1 text-xs text-muted-foreground">
            Sync a repository and trigger a pull request review to populate activity.
          </p>
        </div>
      ) : (
        <div className="h-[260px] w-full">
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={activity} margin={{ top: 8, right: 8, left: -20, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="var(--border)" />
              <XAxis
                dataKey="name"
                stroke="var(--muted-foreground)"
                fontSize={12}
                tickLine={false}
                axisLine={false}
              />
              <YAxis
                allowDecimals={false}
                stroke="var(--muted-foreground)"
                fontSize={12}
                tickLine={false}
                axisLine={false}
              />
              <Tooltip
                cursor={{ fill: "var(--muted)" }}
                contentStyle={{
                  backgroundColor: "var(--popover)",
                  color: "var(--popover-foreground)",
                  borderRadius: "6px",
                  border: "1px solid var(--border)",
                  fontSize: "12px",
                }}
              />
              <Bar
                dataKey="reviews"
                fill="var(--primary)"
                radius={[4, 4, 0, 0]}
                maxBarSize={36}
              />
            </BarChart>
          </ResponsiveContainer>
        </div>
      )}
    </div>
  );
}

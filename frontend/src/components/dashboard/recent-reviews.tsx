"use client";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { mockRecentReviews } from "@/lib/mock-data";
import { CheckCircle2, CircleDashed, XCircle } from "lucide-react";

export function RecentReviews() {
  return (
    <Card className="col-span-3">
      <CardHeader>
        <CardTitle>Recent Reviews</CardTitle>
      </CardHeader>
      <CardContent>
        <div className="space-y-8">
          {mockRecentReviews.map((review) => (
            <div key={review.id} className="flex items-center">
              <div className="flex h-9 w-9 items-center justify-center rounded-full border bg-background">
                {review.status === "completed" && <CheckCircle2 className="h-4 w-4 text-green-500" />}
                {review.status === "processing" && <CircleDashed className="h-4 w-4 text-blue-500 animate-spin" />}
                {review.status === "failed" && <XCircle className="h-4 w-4 text-destructive" />}
              </div>
              <div className="ml-4 space-y-1">
                <p className="text-sm font-medium leading-none">
                  {review.repository} <span className="text-muted-foreground">#{review.prNumber}</span>
                </p>
                <p className="text-sm text-muted-foreground">{review.time}</p>
              </div>
              <div className="ml-auto font-medium">
                {review.issues > 0 ? (
                  <Badge variant="destructive">{review.issues} issues</Badge>
                ) : review.status === "completed" ? (
                  <Badge variant="secondary" className="bg-green-500/10 text-green-500 hover:bg-green-500/20">Clean</Badge>
                ) : null}
              </div>
            </div>
          ))}
        </div>
      </CardContent>
    </Card>
  );
}

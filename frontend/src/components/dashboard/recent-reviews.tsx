"use client";

import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { formatDate, type Review } from "@/lib/api";
import { CheckCircle2, CircleDashed, XCircle } from "lucide-react";

export function RecentReviews({ reviews }: { reviews: Review[] }) {
  return (
    <Card className="col-span-3">
      <CardHeader>
        <CardTitle>Recent Reviews</CardTitle>
      </CardHeader>
      <CardContent>
        <div className="space-y-8">
          {reviews.length === 0 && (
            <p className="text-sm text-muted-foreground">No reviews yet.</p>
          )}
          {reviews.map((review) => (
            <div key={review.id} className="flex items-center">
              <div className="flex h-9 w-9 items-center justify-center rounded-full border bg-background">
                {review.status === "COMPLETED" && <CheckCircle2 className="h-4 w-4 text-green-500" />}
                {["QUEUED", "PROCESSING", "RETRYING", "PENDING"].includes(review.status) && <CircleDashed className="h-4 w-4 animate-spin text-blue-500" />}
                {review.status === "FAILED" && <XCircle className="h-4 w-4 text-destructive" />}
              </div>
              <div className="ml-4 space-y-1">
                <p className="text-sm font-medium leading-none">
                  Review <span className="text-muted-foreground">{review.id.slice(0, 8)}</span>
                </p>
                <p className="text-sm text-muted-foreground">{formatDate(review.createdAt)}</p>
              </div>
              <div className="ml-auto font-medium">
                <Badge variant={review.status === "FAILED" ? "destructive" : "secondary"}>{review.status}</Badge>
              </div>
            </div>
          ))}
        </div>
      </CardContent>
    </Card>
  );
}

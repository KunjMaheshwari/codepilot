import { DashboardStats } from "@/components/dashboard/stats";
import { ActivityChart } from "@/components/dashboard/activity-chart";
import { RecentReviews } from "@/components/dashboard/recent-reviews";
import { Metadata } from "next";

export const metadata: Metadata = {
  title: "Dashboard | CodePilot",
  description: "CodePilot Dashboard",
};

export default function DashboardPage() {
  return (
    <div className="flex-1 space-y-4 pt-6">
      <div className="flex items-center justify-between space-y-2">
        <h2 className="text-3xl font-bold tracking-tight">Dashboard</h2>
      </div>
      <DashboardStats />
      <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-7">
        <ActivityChart />
        <RecentReviews />
      </div>
    </div>
  );
}

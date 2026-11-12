import { Metadata } from "next";
import { DashboardClient } from "@/components/dashboard/dashboard-client";

export const metadata: Metadata = {
  title: "Dashboard | CodePilot",
  description: "CodePilot Dashboard",
};

export default function DashboardPage() {
  return <DashboardClient />;
}

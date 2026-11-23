import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { DashboardStats } from "./stats";

describe("DashboardStats", () => {
  it("renders all four KPI cards with healthy critical hint when criticalIssues is 0", () => {
    render(
      <DashboardStats
        stats={{
          totalReviews: 14,
          issuesDetected: 27,
          criticalIssues: 0,
          activeRepositories: 5,
        }}
      />,
    );

    expect(screen.getByText("Total Reviews")).toBeInTheDocument();
    expect(screen.getByText("14")).toBeInTheDocument();
    expect(screen.getByText("Issues Detected")).toBeInTheDocument();
    expect(screen.getByText("27")).toBeInTheDocument();
    expect(screen.getByText("Critical Issues")).toBeInTheDocument();
    expect(screen.getByText("0")).toBeInTheDocument();
    expect(screen.getByText("No critical findings")).toBeInTheDocument();
    expect(screen.getByText("Active Repositories")).toBeInTheDocument();
    expect(screen.getByText("5")).toBeInTheDocument();
  });

  it("highlights critical issues with danger styling when criticalIssues > 0", () => {
    render(
      <DashboardStats
        stats={{
          totalReviews: 8,
          issuesDetected: 12,
          criticalIssues: 3,
          activeRepositories: 2,
        }}
      />,
    );

    expect(screen.getByText("Requires immediate review")).toBeInTheDocument();
    expect(screen.getByText("3")).toHaveClass("text-destructive");
  });
});

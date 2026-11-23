import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { EmptyState, ErrorBanner } from "./empty-state";

describe("EmptyState and ErrorBanner accessibility", () => {
  it("renders EmptyState with semantic heading, description, and optional action", () => {
    render(
      <EmptyState
        title="No repositories connected"
        description="Synchronize your GitHub account to start reviewing pull requests."
        action={<button type="button">Sync now</button>}
      />,
    );

    expect(screen.getByRole("heading", { level: 3, name: "No repositories connected" })).toBeInTheDocument();
    expect(
      screen.getByText("Synchronize your GitHub account to start reviewing pull requests."),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sync now" })).toBeInTheDocument();
  });

  it("renders ErrorBanner with alert role and invokes onRetry when Retry is clicked", () => {
    const onRetry = vi.fn();
    render(<ErrorBanner message="Failed to load dashboard" onRetry={onRetry} />);

    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("Failed to load dashboard");

    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });
});

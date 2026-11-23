import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { StatusBadge } from "./status-badge";

describe("StatusBadge", () => {
  it("renders status variants and uppercases lowercase inputs", () => {
    const { rerender } = render(<StatusBadge value="completed" />);
    expect(screen.getByText("COMPLETED")).toHaveClass("text-emerald-700");

    rerender(<StatusBadge value="FAILED" />);
    expect(screen.getByText("FAILED")).toHaveClass("text-red-700");

    rerender(<StatusBadge value="retrying" />);
    expect(screen.getByText("RETRYING")).toHaveClass("text-amber-700");
  });

  it("renders severity variants when kind is severity", () => {
    const { rerender } = render(<StatusBadge value="critical" kind="severity" />);
    expect(screen.getByText("CRITICAL")).toHaveClass("text-red-700");

    rerender(<StatusBadge value="HIGH" kind="severity" />);
    expect(screen.getByText("HIGH")).toHaveClass("text-orange-700");

    rerender(<StatusBadge value="LOW" kind="severity" />);
    expect(screen.getByText("LOW")).toHaveClass("text-sky-700");
  });

  it("falls back to muted styling for unknown values", () => {
    render(<StatusBadge value="custom_state" />);
    expect(screen.getByText("CUSTOM_STATE")).toHaveClass("text-muted-foreground");
  });
});

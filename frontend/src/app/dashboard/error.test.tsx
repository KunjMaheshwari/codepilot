import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import DashboardError from "./error";

describe("Dashboard error recovery UI", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("renders recovery message, logs the error, and triggers reset on click", () => {
    const consoleSpy = vi.spyOn(console, "error").mockImplementation(() => {});
    const reset = vi.fn();
    const err = new Error("Unexpected render crash");

    render(<DashboardError error={err} reset={reset} />);

    expect(
      screen.getByRole("heading", { name: "Unable to load workspace view" }),
    ).toBeInTheDocument();
    expect(consoleSpy).toHaveBeenCalledWith(err);

    fireEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(reset).toHaveBeenCalledTimes(1);
  });
});

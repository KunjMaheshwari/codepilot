import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ThemeToggle } from "./theme-toggle";

const setThemeMock = vi.fn();
let currentTheme = "light";

vi.mock("next-themes", () => ({
  useTheme: () => ({
    theme: currentTheme,
    setTheme: setThemeMock,
  }),
}));

describe("ThemeToggle", () => {
  it("toggles between light and dark themes when clicked", () => {
    setThemeMock.mockReset();
    currentTheme = "light";

    const { rerender } = render(<ThemeToggle />);
    const button = screen.getByRole("button", { name: "Toggle theme" });

    fireEvent.click(button);
    expect(setThemeMock).toHaveBeenCalledWith("dark");

    currentTheme = "dark";
    rerender(<ThemeToggle />);
    fireEvent.click(screen.getByRole("button", { name: "Toggle theme" }));
    expect(setThemeMock).toHaveBeenCalledWith("light");
  });
});

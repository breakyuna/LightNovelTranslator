#!/usr/bin/env python3
"""Summarize an existing Gradle HTML profile; never invokes Gradle."""

from html.parser import HTMLParser
from pathlib import Path
import re
import sys


class ProfileRows(HTMLParser):
    def __init__(self):
        super().__init__()
        self.rows = []
        self.row = None
        self.cell = None

    def handle_starttag(self, tag, attrs):
        if tag == "tr":
            self.row = []
        elif tag in ("td", "th") and self.row is not None:
            self.cell = []

    def handle_data(self, data):
        if self.cell is not None:
            self.cell.append(data)

    def handle_endtag(self, tag):
        if tag in ("td", "th") and self.cell is not None:
            self.row.append("".join(self.cell).strip())
            self.cell = None
        elif tag == "tr" and self.row is not None:
            self.rows.append(self.row)
            self.row = None


def seconds(duration):
    return sum(float(value) * {"h": 3600, "m": 60, "s": 1}[unit]
               for value, unit in re.findall(r"(\d+(?:\.\d+)?)([hms])", duration))


def main():
    directory = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("build/reports/profile")
    profiles = sorted(directory.glob("profile-*.html"))
    print("### Lint Gradle task timings\n")
    if not profiles:
        print("No Gradle profile was produced; inspect the Lint step log.")
        return
    profile = profiles[-1]
    parser = ProfileRows()
    parser.feed(profile.read_text(encoding="utf-8"))
    total = next((row[1] for row in parser.rows
                  if len(row) == 2 and row[0] == "Total Build Time"), "unavailable")
    print(f"Profile: `{profile.name}`. Gradle wall time: **{total}**.\n")
    print("Tasks can overlap; their durations must not be added to the wall time.\n")
    print("| Task | Duration | Result |\n|---|---:|---|")
    tasks = [row for row in parser.rows
             if len(row) == 3 and row[0].startswith(":") and row[2] != "(total)"]
    for task, duration, result in sorted(tasks, key=lambda row: seconds(row[1]), reverse=True)[:15]:
        print(f"| `{task}` | {duration} | {result or 'executed'} |")
    print("\nSee the Lint --info log for cache misses and the uploaded profile for all tasks.")


if __name__ == "__main__":
    main()

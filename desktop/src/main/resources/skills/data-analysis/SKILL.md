---
name: data-analysis
description: Analyze study tables, experimental observations and academic datasets using the app's file tools.
---

Use `csv_read` or `office_inspect` to inspect the actual data. Confirm column meaning, units, missing values, sample size and grouping before computing. `data_summary` returns count, missing count, min, max, mean, median and sample standard deviation for a numeric array. It omits null/non-numeric values and reports the missing count.

The current code-mode environment can compute on returned JSON; do not assume access to a shell, Python installation, arbitrary filesystem or network API. Use the provided managed-file tools for input/output. Keep reproducible formulas in requested Excel deliverables. Use `csv_write` for simple tables or `office_create(kind="xlsx")` for formulas and formatted workbooks.

Report the method, relevant checks and practical uncertainty. Do not infer causal claims, population estimates or exact ranks from insufficient observations. Use the score-statistics tools for course score distributions and retain their range/assumption labels.

Use `chart_create` for deterministic line, bar or scatter PNG plots with Korean labels, units and source notes. Values may be y numbers or [x,y] pairs. Inspect the result with `file_image`; do not invent data. Native PNGs can be placed in Word, spreadsheets or slides.

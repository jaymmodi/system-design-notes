# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Repo Is

Personal interview preparation notes for distributed systems, data structures, and Java internals. Primarily markdown reference material, with a small Java `coding-samples` project.

## Repository Layout

```
design/          — numbered distributed systems topics (01–31+)
data-structures/ — storage engine internals (LSM trees, B-trees, skip lists, etc.)
java/            — Java deep dives (concurrency, message brokers, Redis commands)
coding-samples/  — standalone Java scratch project (src/Main.java, src/MapTest.java)
```

Top-level `.md` files are interview-specific guides: `STUDY-PLAN.md`, `NETFLIX-INTERVIEW-GUIDE.md`, `NETFLIX-POPULAR-QUESTIONS.md`, `CONCURRENCY-CODING-PRACTICE.md`, etc.

## Numbering Convention

`design/` files are numbered sequentially. Before creating a new file, check the highest existing number with `ls design/` and increment by 1.

## coding-samples Java Project

Standalone IntelliJ project (`coding-samples/coding-samples.iml`). No build tool — compile and run directly:

```bash
cd coding-samples/src
javac Main.java && java Main
```

## README Maintenance

Always update `README.md` when adding or removing files in `design/`, `java/`, or `data-structures/`. Keep the file listing in README in sync with actual files on disk.

## Content Style

- Notes are sourced from official documentation (AWS, Kafka docs, etc.) — cite the source URL at the top of new files using the `> Summarized from...` blockquote pattern used in existing files.
- Tables are preferred over prose for comparisons and cheat sheets.
- Code blocks use plain identifiers (no package declarations) in design examples.

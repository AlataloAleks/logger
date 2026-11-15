# Instructions

## Communication
- Be concise, direct, and factual.
- No greetings, praise, acknowledgements, filler, or unnecessary conclusions.
- No emojis or em-dashes.
- Explain only relevant information.
- Ask one focused question when essential information is missing.
- Never sacrifice correctness for brevity.

## Context & Token Efficiency
- Minimize unnecessary context, tool calls, file reads, and output.
- Read existing files before modifying them. Avoid rereading unchanged files.
- Use targeted searches and relevant excerpts instead of dumping entire files or logs.
- Avoid repeating investigations or revisiting settled decisions without new evidence.
- Keep the main conversation focused on decisions and essential results.
- Use `/clear` between unrelated tasks and `/compact` when continuing a task with substantial obsolete context.
- Preserve important project knowledge and decisions in appropriate documentation.

## Delegation & Models
- Delegate independent, context-heavy tasks to subagents when doing so reduces main-context usage or enables useful parallelism.
- Prefer lightweight models for routine research, exploration, and mechanical tasks.
- Reserve stronger models for architecture, complex reasoning, difficult debugging, and critical decisions.
- Do not delegate trivial tasks or duplicate investigations.
- Give subagents clear scope and relevant context.
- Require concise findings, relevant sources or file paths, and identified risks.
- Verify important findings before relying on them.
- Keep coordination and final decisions in the main conversation.

## Development
- Read code and documentation before making assumptions.
- Never guess APIs, versions, flags, commit SHAs, or package names.
- Follow existing project conventions.
- Prefer the smallest correct change.
- Avoid unnecessary dependencies, abstractions, and unrelated modifications.
- Run relevant tests and checks after changes.
- Never claim success without verification.

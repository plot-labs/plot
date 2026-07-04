# Plot Product Docs

Status: product direction

This directory holds the product definition, UX direction, positioning notes,
and implementation boundaries for Plot.

## Start Here

| Doc | Use it for |
| --- | --- |
| [Product Definition](product-definition.md) | Product thesis, target users, initial boundaries, product surfaces, and domain language. |
| [Agentic UX Direction](agentic-ux.md) | Session-first UX, embedded task manager, source-backed packs, voice, and citation flow. |
| [YC-Style Product Review](yc-review.md) | Market framing, wedge, risk, and what should be sharpened before pitching. |

## Current Product Shape

```txt
shipping intent
  -> source selection
  -> Writing Blocks
  -> template and voice
  -> update pack
  -> source citations and style guidance
  -> human-controlled publishing outside Plot
```

## Product Boundaries

- Start from shipped work, not a blank composer.
- Treat source adapters as inputs to the update workspace, not the product
  surface itself.
- Make Sessions, Sources, Packs, Voice, and Settings the primary navigation
  model.
- Keep Autonomous as an embedded task manager inside Sessions until parallel or
  recurring work needs its own surface.
- Keep Recipes as a model concept until scheduled recurring work exists.
- Keep publishing human-controlled outside Plot.

## Related Architecture

- [Data Architecture](../architecture/data-architecture.md)
- [Data ERD](../architecture/data-erd.mmd)
- [Project Structure](../architecture/project-structure.md)
- [ADR 0001: Monorepo With Generated Apps](../decisions/0001-monorepo-generated-apps.md)

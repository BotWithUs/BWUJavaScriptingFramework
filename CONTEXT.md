# Host (JBotWithUsV2)

The Java framework that consumes agent state, drives the client, and runs bot
scripts. This glossary covers host / scripter-facing language; cross-cutting
terms (agent, host, client, producer/consumer, script) live in the root
[CONTEXT-MAP.md](../CONTEXT-MAP.md). Where the host renames an engine concept,
the wire/Jagex term is noted so it can be matched back to the agent.

## Scripter-facing entities

**SceneObject**:
The scripter-facing name for a scenery object (tree, wall, door, bank booth, rock…). Renamed from the Jagex term "Location" so scripters aren't confused by "location" appearing to mean a place.
_Avoid_: Location (that's the wire / Jagex term — see the map's translation table)

**Inventory** / **InventoryContainer**:
The scripter-facing umbrella for an item container — `InventoryContainer`, with `Backpack`, `Bank`, `Equipment` as the concrete ones. The Jagex/engine term for the same concept is "container".
_Avoid_: "container" in scripter-facing language; "inventory" to mean specifically the backpack

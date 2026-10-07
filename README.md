<p align="center">
  <img alt="GTNH Planner" src="src/main/resources/gtnhplannerlogo.png" width="128">
</p>

# GTNH Planner

Plan GT New Horizons factories in game. GTNH Planner is the Minecraft side of [gtnhplanner.com](https://gtnhplanner.com):
a flowchart planner that lives in NEI, builds its cards from NEI's own recipes, and balances itself.

## What it does

- **Cards from NEI.** Look an item up with R or U, then press + (or the plan button beside it) to put a recipe on
  the board. Pick the machine; GregTech tiers, amps, coils and overclocks are worked out for you.
- **A board that solves itself.** Set a rate on what you want, or pin a machine count, and every card's machine
  count and every wire's flow follow. Wires route themselves and hop where they cross.
- **Your plans, and everyone's.** Tabs are your open plans; every plan you make is kept in the Library's My plans.
  The Library also holds the public setups from gtnhplanner.com: search them, open one as a plan, or right-click
  something on the overview to see the setups that make it.
- **The same account as the website.** Sign in with your gtnhplanner.com account to post a plan to the public
  library, where it shows on the website and in game.
- **Client side only.** No server mod needed.

## Dependencies

- Minecraft **1.7.10** with Forge, in the GT New Horizons pack
- **Not Enough Items** (GTNH's), **ModularUI2**, **GTNHLib**, **Mixin**

## Building

```bash
./gradlew build
```

## Credits

GTNH Planner started as a fork of [PlanNH](https://github.com/sbancuz/PlanNH) by **Sbancuz**, with UI code by
**[TheYoingLad](https://github.com/TheYoingLad)**. It has since been rebuilt and renamed, so the two never collide.

## Feedback

Bugs and development talk: the GTNH Planner thread on the GT New Horizons Discord (the Discord key in the planner's
top bar takes you there).

## License

MIT — see [LICENSE](LICENSE).

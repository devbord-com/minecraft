# devbord-bugreport for Minecraft

A plugin for Paper (1.20 and newer, Java 17) that lets players report bugs with `/bug <description>`. Every report
becomes a card on devbord with:

- the description (its first line is the title)
- the player name and UUID
- the world, the position and the game mode
- the server software and version
- the label **Bug** (created when it does not exist yet)

When the card is completed on devbord, the player gets a message in the game that the report was fixed (right away
when online, otherwise at the next check while they are online).

## Install

1. In devbord, create an **API key with the "Report only" scope** on the board for bugs and pick the list the reports go
   to (board menu › Integrations › API keys). Such a key cannot read or change anything. This needs the Standard plan or higher: Standard includes 50 reports a month, Premium has no monthly limit.
2. Build the plugin with `mvn package` and copy `target/devbord-bugreport.jar` to the `plugins` folder of the server.
3. Start the server once, then put the address of devbord and the key in `plugins/DevbordBugReport/config.yml` and
   restart.

## Settings

`config.yml` holds the labels, the minimum length, the cooldown per player (120 seconds by default), whether players
are told about fixed bugs, and every message players see. The permission `devbord.bug` (on by default) decides who may
report.

## Notes

- The key stays in `config.yml` on the server; players never see it.
- On top of the cooldown per player, devbord limits each key (120 reports per hour by default, adjustable on the key).
- To find players again, the plugin keeps the receipt and the UUID of each report in `followed.yml`, for at most
  `fixed.follow-days` days.
- This plugin has not been compiled or run against a server yet; build it and try it on a test server first.

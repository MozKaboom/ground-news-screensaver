# Ground News Top Stories

A [Kiosk Satellite](https://kiosksatellite.com) screensaver that cycles through the top stories on [ground.news](https://ground.news). Each story fills the screen with its photo, headline and summary, how many sources cover it and, when Ground News has one, the left, center and right split of that coverage.

This plugin is not affiliated with, endorsed by or sponsored by Ground News. It reads the public ground.news home page the same way a browser does.

## Installation

### Community Plugins (Recommended)

Ground News Top Stories is listed in [Community Plugins](https://kiosksatellite.com/plugins/). Use the button below to open it in your Kiosk Satellite and install it.

[![Install in Kiosk Satellite](https://kiosksatellite.com/img/badges/plugin-add.svg)](https://kiosksatellite.com/redirect/plugin/?repository=MozKaboom/ground-news-screensaver)

Or search for `Ground News Top Stories` in **Settings > Plugin Manager > Browse plugins** in Kiosk Satellite.

### Manual

1. Open **Settings > Plugin Manager** in Kiosk Satellite and turn on **Enable Plugins**.
2. Choose **Add plugin**, paste `https://github.com/MozKaboom/ground-news-screensaver` and choose **Preview**.
3. Review the plugin and choose **Trust and install**.

## Use it

1. Enable **Ground News Top Stories** from its entry row in Plugin Manager.
2. Open **Screensaver > Screensaver mode** and choose **Top Stories (Ground News Top Stories)**. You can also pick it in a screensaver schedule entry.

Kiosk Satellite still decides when the screensaver starts and stops, its brightness, widgets and pixel shift. The plugin only draws the stories.

The stories load when the plugin starts and again on the interval you set. Until the first load finishes the screensaver shows **Loading top stories…**. If ground.news can't be reached, it keeps showing the last stories it loaded and says it's offline. The plugin's subpage shows when the stories last loaded or why they didn't.

The **Refresh stories** action loads the latest stories right away. Run it from the plugin's subpage, a gesture, a drawer shortcut or a Home Assistant button.

## Settings

| Setting | Default | What it does |
| --- | --- | --- |
| Number of stories | 10 | How many stories to cycle through, 3 to 15, in the order the ground.news home page shows them. |
| Include Daily Briefing | Off | Start with the stories from Ground News' Daily Briefing. The briefing is the same in every edition and is mostly US news. |
| Time per story | 15 s | How long each story stays on screen. |
| Refresh every | 30 min | How often to load the latest stories. |
| Show photos | On | Download each story's photo. Turn off to save data. |
| Show summary | On | A short summary under each headline. |
| Show coverage bias | On | The left, center and right split of the sources that cover the story. Not every story has one. |
| Show clock | On | The time and date in the top right corner. |

## Data and privacy

On each refresh the plugin's Java code makes these requests, and no others:

- `https://ground.news/` to read the home page.
- One request per story to Ground News' image CDN (`*.b-cdn.net`) for a reduced copy of the story's photo, unless **Show photos** is off. Photos that haven't changed since the last refresh aren't downloaded again.

The requests carry no account, cookie or kiosk information beyond what any web browser sends. Ground News picks the edition of its home page, such as US or UK, from where the request comes from.

The screensaver page itself makes no network requests. Kiosk Satellite blocks them. The stories and photos are embedded in the page the plugin publishes.

## How it works

Ground News doesn't offer a public API. The plugin reads the story data that the ground.news home page embeds for its own scripts, so a redesign of that page can stop it from finding stories. When that happens the subpage shows **No stories found on ground.news**. Please open an issue.

## Build

Install Python 3, JDK 17 or newer and an Android SDK with a platform and build-tools. Set `ANDROID_HOME` and `JAVA_HOME` as needed.

```sh
python3 tools/test.py
python3 tools/build.py
```

The build creates `dist/ground-news-top-stories-<version>.zip`. To try it, open **Plugin Manager > Developer Tools > Install from ZIP** in Kiosk Satellite.

Releases are built by the GitHub Actions workflow in `.github/workflows/build.yml` when a release is published.

## License

[Apache-2.0](LICENSE). The SDK interfaces in `sdk/` and the build tools in `tools/` come from the [Kiosk Satellite plugin template](https://github.com/jxlarrea/kiosk-satellite-plugins) under the same license.
